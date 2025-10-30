package com.hoho.android.usbserial.unityplugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.app.PendingIntent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.unity3d.player.UnityPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import java.io.ByteArrayOutputStream;

public class SerialPortListener implements com.hoho.android.usbserial.util.SerialInputOutputManager.Listener{

    /// Android entities, initialize from constructor
    private final Context context;
    private final UsbManager usbManager;
    private UsbSerialPort serialPort;
    private UsbDevice currentDevice;
    private SerialInputOutputManager ioManager;
    private Intent currIntent;
    private final PendingIntent permissionPendingIntent; // waiting for getBroadcast that catching permission
    private IntentFilter permissionFilter;


    /// Port Parameters and C# connections
    private UsbSerialProber prober;
    private List<UsbSerialDriver> availableDrivers;
    //private static int availablePortCount;

    // кастомный экшон для фильтра и брокастресивера
    private static final String ACTION_USB_PERMISSION = "com.example.app.USB_PERMISSION";
    private int baudRate = 460800; // передаем из c#
    private int readTimeout = 5; // ms
    private int writeTimeout = 100;
    private static boolean isDebug = true;
    private boolean isConnected = false;
    private int currPortIndex;

    /// Data bytes entities
    // буффер для хранения посылок и периодического сгруза их в c# (таймер задается на стороне юнити, не менее 50мс)
    private final ByteArrayOutputStream baos = new ByteArrayOutputStream(1024); // default capacity 32 bytes

    /// Thread entities
    Thread openedConnectionThread = null;
    private final ReentrantLock reentrantLock = new ReentrantLock();
    private final Object lockObj = new Object();
    private final AtomicBoolean isThreadRunning = new AtomicBoolean(false);
    private final AtomicBoolean shouldStop = new AtomicBoolean(false);

    /// Broadcast Receivers...



///====================================== Constructor for unity ======================================

    public SerialPortListener(@NonNull Context context) {
        this.context = context;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        int intentFlag = PendingIntent.FLAG_IMMUTABLE;

        this.permissionPendingIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(ACTION_USB_PERMISSION) /*INTENT_ACTION_GRANT_USB*/, intentFlag);


        /// Initialize serial port entities
        try{
            // для броадкаст ресиверов
            permissionFilter = new IntentFilter(ACTION_USB_PERMISSION);
// TODO акшоны андроида
/*
// Зарядка подключена/отключена
filter.addAction(Intent.ACTION_POWER_CONNECTED);
filter.addAction(Intent.ACTION_POWER_DISCONNECTED);

// Изменение состояния батареи
filter.addAction(Intent.ACTION_BATTERY_CHANGED);

// Загрузка системы завершена
filter.addAction(Intent.ACTION_BOOT_COMPLETED);

// Изменение режима "В самолете"
filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);

// Изменение состояния Wi-Fi
filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);

// Изменение состояния Bluetooth
filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);

// Подключение к интернету
filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
*/

            // context.registerReceiver(usbReceiver, filter);
            //  registerReceiver(usbReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));
            // TODO: end it
            // ContextCompat.registerReceiver(context, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

            // pending intent end it

            // initialize broadcast instance here

            prober = UsbSerialProber.getDefaultProber();
        }
        catch (Exception e){
            onRunError(e);
        }
    }

///====================================== methods for C# ======================================

    public static int GetPortsCount(Context context){
        int portsCount = 0;
        try{
            UsbManager kostylUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbSerialProber prober = UsbSerialProber.getDefaultProber();

            // usmManager depends of current app's context
            List<UsbSerialDriver> availableDrivers = prober.findAllDrivers(kostylUsbManager);
            portsCount = availableDrivers.size();
        }
        catch(Exception e){
            // onRunError(e);
            SendMessageToUnity("Error: " + e.getMessage());
        }
        return portsCount;
    }
    public static String[] GetPortNames(Context context){
        String[] portNames = null;
        try{
            UsbManager kostylUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbSerialProber prober = UsbSerialProber.getDefaultProber();

            // usmManager depends of current app's context
            List<UsbSerialDriver> availableDrivers = prober.findAllDrivers(kostylUsbManager);
            portNames = new String[availableDrivers.size()];
            for(int i = 0; i<availableDrivers.size(); i++){
                portNames[i] = availableDrivers.get(i).getDevice().getDeviceName();
            }
            if(isDebug){
                SendMessageToUnity("available ports count: " + availableDrivers.size());
            }

//            StringJoiner joiner = new StringJoiner(",");
//            list.forEach(item -> joiner.add(item.toString());
//            joiner.toString();
        } catch (Exception e) {
            // TODO DEBAAAAAAAAAAAAGH
            SendMessageToUnity("Error: " + e.getMessage());
        }
        return portNames;
    }

    public void SetIsDebug(boolean val){
        isDebug = val;
        SendMessageToUnity("new isDebug value: " + isDebug);
    }

    public boolean IsConnected(){
        SendMessageToUnity("Asking isConnected: " + isConnected);
        return isConnected;
    }

    public void SetReadBufferDelay(int delay){
        readTimeout = delay;
        SendMessageToUnity("readTimeout: " + readTimeout + " ms");
    }
    public int GetReadBufferDelay(){
        return 0;// readTimeout;
    }

    public int PortIndex() {
        return currPortIndex;
    }
    public String PortName(){
        String[] portNames = GetPortNames(context);
        return portNames[currPortIndex];
    }
    public int GetBaudRate(){
        return baudRate;
    }

    // write
    public void SendBytes(byte[] bytes){
        if(!isConnected) {
            SendMessageToUnity("Cannot write, not connected");
            return;
        }
        if(serialPort == null){
            SendMessageToUnity("Cannot write, no serial port");
            return;
        }

        try
        {
            serialPort.write(bytes, writeTimeout);
        }
        catch (Exception e)
        {
            onRunError(e);
        }
    }
    // TODO: hz zachem eto
    //void ReadBytes(byte[] bytes);
//    void HandleError(String message){
//        //TODO
//    }

    // in unity - check if it null or empty
    public byte[] FlushReadBuffer(){
        reentrantLock.lock();
        byte[] result;
        try{
            result = baos.toByteArray();
            baos.reset();
        }
        catch (Exception e){
            onRunError(e);
            result = null;
        }
        finally {
            reentrantLock.unlock();
        }
        return result;
    }

    public boolean DisconnectSerial() {
        try{
            if(isConnected){
                ///  Close the thread
                closeConnectionThread();

                stopIoManager();
                serialPort.close();

                isConnected = false;
            }

            if(usbReceiver != null){
                // unregisterReceiver(permissionReceiver);
            }
        }
        catch(Exception e){
            onRunError(e);
            return false;
        }
        return true;
    }

    public boolean ConnectSerial(int port, int baudRate){
        if(isConnected){
            SendMessageToUnity("Already connected");
            return false;
        }
        if(usbManager == null){
            // TODO переинициализировать - не выйдет тк final, терпим
            // usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            SendMessageToUnity("UsbManager is null");
            return false;
        }
        this.baudRate = baudRate;
        currPortIndex = port;

        availableDrivers = GetAvailableDrivers();
        UsbSerialDriver driver = availableDrivers.get(port);
        // driver.getDevice().equals(device)
        currentDevice = driver.getDevice();

        /// Try to close connect thread
        reentrantLock.lock();
        try{
            try{
                if(isThreadRunning.compareAndSet(true, false))
                    closeConnectionThread();
                // TODO clear close connection !!!
                if(isThreadRunning.compareAndSet(false, true)){
                    openConnectionThread(currentDevice);
                }
            }
            finally{
                reentrantLock.unlock();
            }
            ///  there re opening of thread
            // start thread with loopa - achieve permission

            openConnectionToDevice(driver);
        }
        catch(Exception e){
            onRunError(e);
            return false;
        }
        return true;
    }


    ///====================================== Private methods ======================================

    private void closeConnectionThread(){
        reentrantLock.lock();
        try{
            if(openedConnectionThread != null && isThreadRunning.compareAndSet(true, false)){
                try{
                    openedConnectionThread.join(500);
                }
                catch(InterruptedException e){
                    onRunError(e);
                    Thread.currentThread().interrupt();
                }
                finally{
                    openedConnectionThread = null;
                }
                if(isDebug) SendMessageToUnity("Connection thread stopped");
            }
        }
        finally{
            reentrantLock.unlock();
        }
    }

    private void openConnectionThread(UsbDevice device){
        // here we requestin permissions
        if(usbManager == null){
            if(isDebug) SendMessageToUnity("UsbManager is null");
            return; // no, try again
        }
        if(device == null){
            return;
        }
        try{
            while(!(usbManager.hasPermission(device))){
                usbManager.requestPermission(device, permissionPendingIntent);
                Thread.sleep(500);
            }
        }
        catch(InterruptedException e){
            onRunError(e);
        }

    }

    private void openConnectionToDevice(UsbSerialDriver driver) {
        // при подключ к конкр драйв
        // usbManager.requestPermission(driver.getDevice(), permissionIntent);

        if(isDebug) SendMessageToUnity("CurrentDevice name: " + currentDevice.getDeviceName());
        if(isDebug) SendMessageToUnity("Trying to connect to certain device. IsConnected: " + isConnected);
        UsbDeviceConnection deviceConnection = usbManager.openDevice(currentDevice);

        if (deviceConnection == null) {
            isConnected = false;
            if(isDebug) SendMessageToUnity("DeviceConnection is null");
            return;
        }

        try {
            serialPort = driver.getPorts().get(0);

            serialPort.open(deviceConnection);
            serialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            // TODO  requestPermission
            startIoManager(serialPort);

            isConnected = true;
        }
        catch (Exception e) {
            try {
                onRunError(e);
                if(isDebug) SendMessageToUnity("Closing connection");
                serialPort.close();
            }
            catch (Exception ex) {
                onRunError(ex);
            } // if ports is empty
        }
    }

    private void startIoManager(UsbSerialPort port) {
        // ioManager (port, Listener)
        stopIoManager();
        ioManager = new SerialInputOutputManager(serialPort, this);
        ioManager.start();
        // finally can read
        // Executors.newSingleThreadExecutor().submit((Runnable) ioManager);
    }

    private void stopIoManager() {
        if (ioManager != null) {
            ioManager.stop();
            ioManager = null;
        }
    }

    private List<UsbSerialDriver> GetAvailableDrivers(){
        availableDrivers.clear();
        availableDrivers = prober.findAllDrivers(usbManager);
        return availableDrivers;
    }


///====================================== Broadcast receiver ======================================
// awakes on requesting permission (loopa in createConnectionThread) with ACTION_USB_PERMISSION
// permission intent
// usbManager.requestPermission(device, permissionIntent);
        private boolean isUsbReceiverRegistered = false;
private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
// не нужна тут синхронизация, onReceive вызывается только в главном потоке
        try{
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(usbManager.EXTRA_PERMISSION_GRANTED, false);

// проверяем что это то же устройство, для которого запрашивали разрешение
                if(device != null && device.equals(currentDevice)){
                    // public static final String EXTRA_PERMISSION_GRANTED = "permission";
                    if(granted){
                        if(isDebug) SendMessageToUnity("Extra permission granted");
                        if(isConnected){
                            /// !!
                            DisconnectSerial();
                        }
                        else{
                            openConnectionToDevice(availableDrivers.get(currPortIndex));
                        }
                    }
                    else{
                        if(isDebug) SendMessageToUnity("Extra permission denied");
                    }
                }
                else{
// TODO device should be initialized and correct
                    if(isDebug) SendMessageToUnity("The device must be initialized and correct");
                }

            }
        }
        catch(Exception e){
            onRunError(e);
        }
    }

};

private void registerReceiver(){
    if(usbReceiver == null){
        // TODO jopa
        if(isDebug) SendMessageToUnity("UsbReceiver is null");
        // add actions, or no messages will be able to attachment
    }
    if(!isUsbReceiverRegistered){
        ContextCompat.registerReceiver(context, usbReceiver, permissionFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
        return;
    }
    if(isDebug) SendMessageToUnity("UsbReceiver somehow already registered");
}

private void unregisterUsbReceiver(){
    if(isDebug) SendMessageToUnity("Unregister usbReceiver");
    context.unregisterReceiver(usbReceiver);
}


///====================================== Interface methods ======================================

    @Override
    public void onNewData(byte[] data) {
        reentrantLock.lock();
        try{
            baos.write(data,/*оффсет в принмаемом массиве*/ 0, data.length);
        }
        catch(Exception e){
            // TODO: handle exception
        }
        finally {
            reentrantLock.unlock();
        }
    }

    @Override
    public void onRunError(Exception e) {
        String errorMsg = "Error: " + e.getMessage();
        SendMessageToUnity(errorMsg);
    }

///====================================== Unity logger ======================================

    private static void SendMessageToUnity(String msg){
        UnityPlayer.UnitySendMessage("Script_SerialProxy", "OnBroadcastReceived", msg);
    }

}
