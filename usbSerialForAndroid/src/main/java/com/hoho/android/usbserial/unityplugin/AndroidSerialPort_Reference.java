package com.hoho.android.usbserial.unityplugin;

import java.util.*;
import java.util.ArrayList;
import java.util.List;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.pdf.models.ListItem;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import androidx.core.content.ContextCompat;

import com.goida.serialportrebuild.driver.UsbSerialPort;
import com.goida.serialportrebuild.driver.UsbSerialDriver;
import com.goida.serialportrebuild.driver.UsbSerialProber;
import com.goida.serialportrebuild.util.SerialInputOutputManager;
import com.goida.serialportrebuild._additional.AndroidSerialPortMessageHandler;

// основной плагин
public class AndroidSerialPort implements com.goida.serialportrebuild.util.SerialInputOutputManager.Listener,
        com.goida.serialportrebuild._additional.AndroidSerialPortMessageHandler {

    /// Android entities, initialize from constructor
    private final Context context;
    private final UsbManager usbManager;
    private UsbSerialPort serialPort;
    private UsbDevice currentDevice;
    private SerialInputOutputManager ioManager;
    private final PendingIntent permissionIntent;

    private Intent currIntent;

    /// Port Parameters
    private UsbSerialProber prober;
    private List<UsbSerialDriver> availableDrivers;
    private List<String> availablePortsNames;
    private static int availablePortCount;

    private static final String ACTION_USB_PERMISSION = "com.example.app.USB_PERMISSION";
    private static final int DEFAULT_BAUD_RATE = 460800; // передаем из c#
    private int baudRate = DEFAULT_BAUD_RATE;

    /// Data entities

    Thread connection = null;

    private final byte[] buffer = new byte[1024];
    List<Byte> dataBuf = Collections.synchronizedList(new ArrayList<>()); // этот список должен получать все считываемые байты

    private int buffLen = 0; // счетчик байтов в буффере


    // set timeouts by unity
    private int writeTimeout = 200; //ms
    private int readTimeout = 5; // таймаут чтения

    /// C# connections
    private boolean isConnected = false;
    private int portIndex; // current
    private String portName;
    private boolean isCustomSettings = false;

    public boolean IsCustomSetting(){
        return isCustomSettings;
    }

    public boolean IsConnected(){
        return isConnected;
    }
    public int     PortIndex(){
        return portIndex;
    }
    public String PortName(){
        return portName;
    }
    public int     BaudRate(){
        return baudRate;
    }


    public static int GetPortsCount(){
        return availablePortCount;
    }

    public String[] GetPortNames(){
        String[] names = new String[availablePortCount];
        availablePortsNames.toArray(names);
        return names;
    }

    /// Call him from Unity,
    public AndroidSerialPort(Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        int intentFlag = PendingIntent.FLAG_IMMUTABLE;
        this.permissionIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION) /*INTENT_ACTION_GRANT_USB*/, intentFlag);
        InitializeSerialPort();
    }

    private void InitializeSerialPort(){
        try{
            IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
            // context.registerReceiver(usbReceiver, filter);
            //  registerReceiver(usbReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));
            ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

            prober = UsbSerialProber.getDefaultProber();

            // при подключ к конкр драйв
            // usbManager.requestPermission(driver.getDevice(), permissionIntent);

            // передаем его через юнити
            // usbManager = activity.Call<AndroidJavaObject>("getSystemService", "usb");

            availableDrivers = prober.findAllDrivers(usbManager);
            // постоянно обновлять
            availablePortCount = availableDrivers.size();

            availablePortsNames.clear();
            for(int i = 0; i<availablePortCount; i++){
                String deviceName = availableDrivers.get(i).getDevice().getDeviceName();
                availablePortsNames.add(deviceName);
            }

        }
        catch (Exception e){
            // не isConnected, он именно при подсоединении тру
            // boolean false
            onRunError(e);
        }
    }

    // наверх посылается к-во доступных портов и сами порты
    // by numer
    public boolean Connect(int port, int baudRate, int readTimer){
        if(isConnected){
            // Handle connected already
            return false;
        }
        if(usbManager == null){
            // Handle it
            return false;
        }
        this.baudRate = baudRate;
        SetReadTimeout(readTimer);
        portIndex = port;

        UsbSerialDriver driver = availableDrivers.get(port);
        // driver.getDevice().equals(device)
        currentDevice = driver.getDevice();

        // starts thread connection
        // Runnable createConnectionThread;
        // connection = new Thread(String.valueOf( createConnectionThread()));
        connection = new Thread(new Runnable() {
            @Override
            public void run() {
                createConnectionThread();
            }
        });
        connection.start();  ;// .Start();
        // usbManager.requestPermission(currentDevice, permissionIntent);

        openConnectionToDevice(driver);

        return true;
    }

    // right to device
    private void openConnectionToDevice(UsbSerialDriver driver) {

        // check
        UsbDeviceConnection deviceConnection = usbManager.openDevice(currentDevice);

        if (deviceConnection == null) {
            isConnected = false;
            // Handle it
            return;
        }
        isConnected = true;

        serialPort = driver.getPorts().get(0);
        try {
            serialPort.open(deviceConnection);
            serialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            startIoManager();
        }
        catch (Exception e) {
            try {
                serialPort.close();
            }
            catch (Exception ex) {
                onRunError(ex);
            } // if ports is empty
        }
    }


    private boolean createConnectionThread(){
        if(currentDevice == null) {
            // OnRunError(new Exception("currentDevice not initialized"));
        }
        try {
            while (!usbManager.hasPermission(currentDevice)) {
                usbManager.requestPermission(currentDevice, permissionIntent);
                Thread.sleep(500);
            }
        }
        catch(Exception e) {
            onRunError(e);
            // softDisconnect
            return false;
        }
        return true;
    }

    private void stopIoManager() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
    }

    private void startIoManager() {
        stopIoManager();
        ioManager = new SerialInputOutputManager(serialPort, this);
        ioManager.start(); // само подключение
        // Executors.newSingleThreadExecutor().submit((Runnable) ioManager);
    }

    // можно вызв из unity в тч
    public /*boolean*/ void Disconnect() {
        availableDrivers.clear();
        availablePortsNames.clear();

        stopIoManager();
        isConnected = false;

        //buffer.clean();
        dataBuf.clear();
        //buffLen = 0;
        // !!

        try {
            if (serialPort != null) {
                serialPort.close();
            }

            if(usbReceiver != null){
                context.unregisterReceiver(usbReceiver);
            }

            // } catch (IOException ignored) {}
        } catch (Exception e){
            onRunError(e);

        }

        try{
            if(connection != null)
                connection.join();
        }
        catch(InterruptedException e){
            onRunError(e);
        }

        // return false;
    }


    // не требуется
    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {

                synchronized (this) {
                    // or connect here
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if(device != null && device.equals(currentDevice)){
                        // public static final String EXTRA_PERMISSION_GRANTED = "permission";
                        boolean granted = intent.getBooleanExtra(usbManager.EXTRA_PERMISSION_GRANTED, false);

                        if(granted){
                            if(isConnected){
                                /// !!
                                Disconnect();
                                isConnected = false;
                            }
                            else{
                                // Connect(portIndex, baudRate, readTimeout); // из unity, когда нужно изм настройки

//                                connection = new Thread(String.valueOf( createConnectionThread()));
//                                connection.start();  ;// .Start();

                                openConnectionToDevice(availableDrivers.get(portIndex));
                            }
                        }

                    }

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)){
                        try{
                            if (device != null) {
                                List<UsbSerialDriver> drivers = GetAvailableDrivers();
//                            for (UsbSerialDriver driver : drivers) {
//                                if (driver.getDevice().equals(device)) {
//                                    openConnection(driver);
//                                    break;
//                                }
//                            }
                            }
                        } catch (Exception e) {
                            onRunError(e);
                        }
                    }

                }

            }
        }

    };

    /*
связь с хрюнити через броадкаст
    public class EventSender {
    public static void sendEvent(Context context, String message) {
        Intent intent = new Intent("com.example.MY_EVENT");
        intent.putExtra("msg", message);
        context.sendBroadcast(intent);
    }
}
C# (Unity):

using UnityEngine;

public class BroadcastReceiverExample : MonoBehaviour
{
    AndroidJavaObject context;
    AndroidJavaObject receiver;

    void Start()
    {
        using (var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer"))
            context = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");

        receiver = new AndroidJavaProxy("android.content.BroadcastReceiver") {
            public void onReceive(AndroidJavaObject context, AndroidJavaObject intent) {
                string msg = intent.Call<string>("getStringExtra", "msg");
                Debug.Log("Broadcast получен: " + msg);
            }
        };

        context.Call("registerReceiver", receiver, new AndroidJavaObject("android.content.IntentFilter", "com.example.MY_EVENT"));
    }
}
    * */

    private void HandleUnityMessage(/*Context context,*/ String message ){
        // intent
    }



    public List<UsbSerialDriver> GetAvailableDrivers() {
        availableDrivers.clear();
        availableDrivers = prober.findAllDrivers(usbManager);
        return availableDrivers;
    }


    public void SetReadTimeout(int timeout){
        if(timeout < 5){
            //HandleUnityMessage("Attempt to set incorrect read timeout");
            //timeout = 5;
            readTimeout = 5;
        }
        if(timeout > 100){
            readTimeout = 100;
        }
        readTimeout = timeout;
    }

    public void SetWriteTimeout(int timeout){
        if(timeout < 50){
            //HandleUnityMessage("Attempt to set incorrect read timeout");
            writeTimeout = 50;
        }
        if(timeout > 500){
            writeTimeout = 500;
        }
        writeTimeout = timeout;
    }




/// как обрабатывать, если долго не запрашивается буффер из приложения --

    //не будет ли пробл с вызовом синхр из юнити
    /// Draft
    public synchronized byte[] SendBytes(){
        if(buffLen == 0)
            return new byte[0];


//        if (handler != null)
//            handler.obtainMessage(0, arg0).sendToTarget();

        // synchronized (buffer){
        byte[] res = new byte[buffLen];
        if (buffLen >= 0) System.arraycopy(buffer, 0, res, 0, buffLen + 1);
        buffLen = 0;
        // }
        return res;
    }
    /// Draft
    private synchronized void AddBytesToBuffer(byte[] data){
        //synchronized (buffer){
        int available = buffer.length - buffLen;
        int writable = Math.min(available, data.length);

        if(available < data.length){
            // raise error
        }
        else{
//            for (int i = 0; j < data.length;i++) {
//                buffer[buffLen + i] = data[i];
//            }
            System.arraycopy(data, 0, buffer, buffLen, data.length);
            buffLen += data.length; // всегда + 16
        }

        //}
    }

/*

    public void HandleUnityMessage(String message){

        ///  TODO !!!

//        com.unity3d.player.UnityPlayer.UnitySendMessage(
//                "AndroidSerialPortWrapper",      // Имя GameObject в Unity, который будет принимать сообщение
//                "OnUsbPermissionResult", // Имя метода в скрипте C#
//                message                // Параметр (строка) передаётся в метод
//        );

    }*/


    // read write
    public void write(byte[] data)
    {
        if(!isConnected || serialPort == null)
        {
            HandleUnityMessage("Cannot write cause of not connected or no serial port");
            return;
        }
        try
        {
            serialPort.write(data, writeTimeout);
        }
        catch (Exception e)
        {
            onRunError(e);
        }
    }


    // Interface methods
    @Override
    public void onNewData(byte[] data) {
        // для возврата в основной поток юнити
        //mainHandler.post(() -> onDataReceived(result));
        // handler.obtainMessage(MESSAGE_FROM_SERIAL_PORT, data).sendToTarget();
        AddBytesToBuffer(data);


    }

    @Override
    public void onRunError(Exception e) {

        HandleUnityMessage("Exception: " + e.getMessage());
        // Disconnect
    }

    @Override
    public void SetPortName(String portName) {

    }

    @Override
    public void ChangeConnected(boolean isConnected) {
        if (isConnected){
            Disconnect();
        }
        else{
            // try connect
        }
    }

    @Override
    public void HandleError(String errorMessage) {
        // raise to unity
        ///  DEBUG CONNECTION TO UNITY
    }

    @Override
    public void ReadBytes(byte[] bytes) {
        // is it flush buffer once per 40ms ?
    }
}
