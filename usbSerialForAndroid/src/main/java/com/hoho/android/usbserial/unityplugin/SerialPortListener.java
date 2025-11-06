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
    private int readBufferDelay = 40; // to flushBuffer
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
    private final AtomicBoolean isPermissionGranted = new AtomicBoolean(false);

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
            SendDebugMessageToUnity("available ports count: " + availableDrivers.size());

//            StringJoiner joiner = new StringJoiner(",");
//            list.forEach(item -> joiner.add(item.toString());
//            joiner.toString();
        } catch (Exception e) {
            SendMessageToUnity("ERROR: " + e.getMessage()); // this method static
        }
        return portNames;
    }

    public void SetIsDebug(boolean val){
        isDebug = val;
        SendDebugMessageToUnity("Now isDebug is " + isDebug);
    }

    public boolean IsConnected(){
        // SendDebugMessageToUnity("Asking isConnected: " + isConnected);
        return isConnected;
    }

    ///  default 40 ms
    public void SetReadBufferDelay(int delay){
        if(delay < 10) readBufferDelay = 10;
        if(delay > 60/*1000*/) readBufferDelay = 60;
        else readBufferDelay = delay;
        SendDebugMessageToUnity("New readBufferDelay: " + readBufferDelay);
    }

    public int GetReadBufferDelay(){
        return readBufferDelay;
    }

    public void SetReadTimeout(int delay){
        if(delay < 5) readTimeout = 5;
        if(delay < readBufferDelay) readTimeout = readBufferDelay;
        readTimeout = delay;
        SendDebugMessageToUnity("readTimeout: " + readTimeout + " ms");
    }

    public void SetWriteTimeout(int delay){
        writeTimeout = delay;
        SendDebugMessageToUnity("writeTimeout: " + writeTimeout + " ms");
    }

    public int GetReadTimeout(){
        return readTimeout;// readTimeout;
    }

    public int GetWriteTimeout(){
        return writeTimeout;// readTimeout;
    }

    public int GetPortIndex() {
        return currPortIndex;
    }
    public String PortName(){
        String[] portNames = GetPortNames(context);
        return portNames[currPortIndex];
    }
    public int GetBaudRate(){
        return baudRate;
    }

    public void SetBaudRate(int val) {
        if(val < 0) baudRate = 600;
        else baudRate = val;
        SendDebugMessageToUnity("Current BaudRate is: " + baudRate);
    }

    // write
    public void SendBytes(byte[] bytes){
        if(!isConnected) {
            SendDebugMessageToUnity("Cannot write, not connected");
            return;
        }
        if(serialPort == null){
            SendDebugMessageToUnity("Cannot write, no serial port");
            return;
        }
        SendDebugMessageToUnity("Cannot write, no serial port");
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
                ///  Close the thread - ?? !!
                closeConnectionThread();

                stopIoManager();
                serialPort.close();

                isConnected = false;
            }
            unregisterUsbReceiver();
        }
        catch(Exception e){
            onRunError(e);
            return false;
        }
        return true;
    }

    public boolean ConnectSerial(int port, int baudRate){
        if(isConnected){
            // а в тот же драйвер или нет
            SendDebugMessageToUnity("Already connected");
            return false;
        }
        else{
            SendDebugMessageToUnity("There is no connection yet");
        }
        if(usbManager == null){
            /// переинициализировать - не выйдет тк final, терпим
            ///  непонятно что делать при onDestroy в unity
            // usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            SendDebugMessageToUnity("UsbManager is null");
            return false;
        }
        else{
            SendDebugMessageToUnity("UsbManager exists");
        }

        SetBaudRate(baudRate);
        try {
            availableDrivers = GetAvailableDrivers();
            SendDebugMessageToUnity("check drivers count: " + availableDrivers.size()); // stupid debug
            if(port > availableDrivers.size() || port <= 0){
                SendDebugMessageToUnity("Input correct port index");
                return false;
            }
            currPortIndex = port;

            UsbSerialDriver driver = availableDrivers.get(port);
            // driver.getDevice().equals(device)

            /// Try to close connect thread
            //reentrantLock.lock();
            synchronized (this){
                currentDevice = driver.getDevice();

                // до реквеста пермишна, должен быть уже назначен currentDevice
                registerUsbReceiver();

                if(isThreadRunning.compareAndSet(true, false)){
                    SendDebugMessageToUnity("Waiting to 1 second for closing read thread");
                    closeConnectionThread();
                }

                // if(isThreadRunning.compareAndSet(false, true)){
                if(!isThreadRunning.get()){
                    // TODO async
                    openConnectionThreadThruPermission(currentDevice, driver);
                    isThreadRunning.set(true);
                }
                //  openConnectionToDevice(driver);
            }

                isConnected = true;
                return true;

//            finally{
//                reentrantLock.unlock();
//            }
            ///  there re opening of thread
            // start thread with loopa - achieve permission

        }
        catch(Exception e){
            isConnected = false;
            onRunError(e);
            return false;
        }
    }

///====================================== The loopa ======================================

//    private void StartTransferToUnity(){
//
//        // starts thread when fully connected
//        while(isConnected){
//
//        }
//
//    }

/// ====================================== Private methods ======================================

    private boolean requestPermissionBeforeLoopa(UsbSerialDriver driver){
        if(usbManager.hasPermission(currentDevice)){
            openConnectionToDevice(driver);
            return true;
        }
        usbManager.requestPermission(currentDevice, permissionPendingIntent);
        return false;
    }

    // async enough
    private /*synchronized*/ void openConnectionThreadThruPermission(UsbDevice device, UsbSerialDriver driver){
        // here we requestin permissions
        reentrantLock.lock();
        try{
            // closeConnectionThread();
            if(usbManager == null){
                SendDebugMessageToUnity("UsbManager is null");
                //return; // no, try again
                Thread.sleep(100);
                // openConnectionThreadThruPermission(device);
            }
            if(device == null){
                SendDebugMessageToUnity("UsbManager is null");
                // Thread.sleep(100);
                // openConnectionThreadThruPermission(device);
            }

//            while(!(usbManager.hasPermission(device))){
////                PendingIntent.getBroadcast(context, 0,
////                        new Intent(ACTION_USB_PERMISSION) /*INTENT_ACTION_GRANT_USB*/, PendingIntent.FLAG_IMMUTABLE)
//                usbManager.requestPermission(device, permissionPendingIntent);
//                SendDebugMessageToUnity("Waiting for permission with usbManager...");
//                Thread.sleep(100);
//            }
            // may generate nullptr
            if(!usbManager.hasPermission(device)){
                usbManager.requestPermission(device, permissionPendingIntent);
                SendDebugMessageToUnity("Requesting permission with usbManager...");
                return;
            }
            // запуск нового треда
            openedConnectionThread = new Thread(() -> openConnectionToDevice(driver));
            openedConnectionThread.start();
//            openConnectionToDevice(driver);
        }
        catch(Exception e){
            onRunError(e);
        }
        finally{
            reentrantLock.unlock();
        }
    }

    private void openConnectionToDevice(UsbSerialDriver driver) {
        // при подключ к конкр драйв
        // usbManager.requestPermission(driver.getDevice(), permissionIntent);

        SendDebugMessageToUnity("CurrentDevice name: " + currentDevice.getDeviceName());
        SendDebugMessageToUnity("Trying to connect to certain device. IsConnected: " + isConnected);

        UsbDeviceConnection deviceConnection = usbManager.openDevice(currentDevice);// не сработает без получения permission

        if (deviceConnection == null) {
            isConnected = false;
            SendDebugMessageToUnity("DeviceConnection is null");
            return;
        }

        try {
            serialPort = driver.getPorts().get(0);
            serialPort.open(deviceConnection);
            serialPort.setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            startIoManager(serialPort);
            isConnected = true;
            SendDebugMessageToUnity("Connection thread is opened");

            while(!Thread.currentThread().isInterrupted()){
                continue;
            }

        }
        catch (Exception e) {
            onRunError(e);
        }
        finally{
            try {
                isConnected = false;
                SendDebugMessageToUnity("Closing connection");
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

    private synchronized void closeConnectionThread(){
        ///  mode flag ??
        // reentrantLock.lock();
        try{
            if(openedConnectionThread != null && isThreadRunning.get()){
                try{
                    openedConnectionThread.join(1000);
                }
                catch(InterruptedException e){
                    onRunError(e);
                    SendDebugMessageToUnity("Try interrupt thread..");
                    // Thread.currentThread().interrupt();
                    openedConnectionThread.interrupt();
                }
                finally{
                    openedConnectionThread = null;
                }
                SendDebugMessageToUnity("Connection thread stopped");
                isThreadRunning.set(false);
            }
        }
        catch(Exception e){
            onRunError(e);
        }
//        finally{
//            reentrantLock.unlock();
//        }
    }

    private List<UsbSerialDriver> GetAvailableDrivers(){
        try{
            availableDrivers.clear();
            availableDrivers = prober.findAllDrivers(usbManager);
            return availableDrivers;
        }
        catch(Exception e){
            onRunError(e);
            return availableDrivers;
        }
    }

///====================================== Broadcast receiver ======================================
// awakes on requesting permission (loopa in createConnectionThread) with ACTION_USB_PERMISSION
// permission intent
// usbManager.requestPermission(device, permissionIntent);
// private boolean isUsbReceiverRegistered = false;
private AtomicBoolean isUsbReceiverRegistered = new AtomicBoolean(false);
// создаем этот экземпл ресивера один раз и не занулляем
private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
// не нужна тут синхронизация, onReceive вызывается только в главном потоке
        try{
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {

                // handle permission
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

// проверяем что это то же устройство, для которого запрашивали разрешение
                if(device != null && device.equals(currentDevice /* != null*/ /*curr device is always true*/)){

                    // public static final String EXTRA_PERMISSION_GRANTED = "permission";
                    if(granted && !isConnected){
                        isPermissionGranted.set(true);
                        SendDebugMessageToUnity("[UsbReceiver] Extra permission granted for device");
                            // передам сюда драйвер с главного потока, он не нулл
//                         UsbSerialDriver driver = availableDrivers.get(currPortIndex);
//                        new Thread( () -> {
//                            openConnectionToDevice(driver);
//                        }).start();

//                        if(isConnected){
//                            SendDebugMessageToUnity("[UsbReceiver] Already connected? so ignoring granted permission");
//                            // DisconnectSerial(); // там вызывается анрегистер ресивера внутри самого ресивера
//                        }
//                        else{
//                            SendDebugMessageToUnity("[UsbReceiver] Able to connect to device");
//                            openConnectionToDevice(availableDrivers.get(currPortIndex));
//                        }
                    }
                    else{
                        if(!granted){
                            SendDebugMessageToUnity("[UsbReceiver] Extra permission denied");
                            isPermissionGranted.set(false);
                        }
                        if(isConnected)
                            SendDebugMessageToUnity("Somehow already connected");
                    }
                }
                else{
                    SendDebugMessageToUnity("[UsbReceiver] The device must be initialized and correct");
                }

            }
        }
        catch(Exception e){
            SendMessageToUnity("Error during receiving permission broadcast");
            onRunError(e);
        }
    }

};

private void registerUsbReceiver(){
    if(usbReceiver == null){
        SendDebugMessageToUnity("UsbReceiver is null");
        return;
    }

    try {
        if(isUsbReceiverRegistered.compareAndSet(false, true)){
            ContextCompat.registerReceiver(context, usbReceiver, permissionFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
            SendDebugMessageToUnity("UsbReceiver registered");
//            isUsbReceiverRegistered = true;
            return;
        }
        SendDebugMessageToUnity("UsbReceiver somehow was already registered");
    }
    catch(Exception e){
//        isUsbReceiverRegistered = false;
        isUsbReceiverRegistered.set(false);
        onRunError(e);
    }
}

private void unregisterUsbReceiver(){
    //if(isUsbReceiverRegistered.compareAndSet(true, false) && usbReceiver != null){ // отписываемся без проверок любой ценой, но бесплатно
        isUsbReceiverRegistered.set(false);
        try{
            context.unregisterReceiver(usbReceiver);
            SendDebugMessageToUnity("UsbReceiver unregistered");
        }
        catch(Exception e){
            // isUsbReceiverRegistered.set();
            onRunError(e);
        }
    //}
}


///====================================== Interface methods ======================================

    @Override
    public void onNewData(byte[] data) {
        reentrantLock.lock();
        try{
            baos.write(data,/*оффсет в принимаемом массиве*/ 0, data.length);
        }
        catch(Exception e){
            onRunError(e);
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
        UnityPlayer.UnitySendMessage("Script_UAndReceive", "OnBroadcastReceived", msg);
        // Base64.encodeToString(data, Base64.DEFAULT)); // если надо будет отправлять байты
    }

    private static void SendDebugMessageToUnity(String msg){
        if(isDebug) UnityPlayer.UnitySendMessage("Script_UAndReceive", "OnBroadcastReceived", msg);
    }

}
