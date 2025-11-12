package com.hoho.android.usbserial.unityplugin;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.unity3d.player.UnityPlayer;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.locks.ReentrantLock;

public class SerialPortMediator implements /*IUnityBufferFetcher, */IUnityController, com.hoho.android.usbserial.util.SerialInputOutputManager.Listener {


/// ________________________ Entities (from old script) ________________________

    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager serialIoManager;
    private BroadcastReceiver permissionReceiver;
    private UsbSerialDriver driver;

    private List<UsbSerialDriver> availableDrivers;

    private int availablePortCount = 0;


/// ________________________ parameters ________________________

    private int baudRate = 460800;
    private static String k_usbPermission = "com.example.app.USB_PERMISSION";
    private volatile boolean isDeviceConnected = false;
    private boolean isDebug = true;
    private int readTimeout = 5; // ms
    private int writeTimeout = 100;
    private int readBufferDelay = 40;

    /// ________________________ Entities from Unity ________________________

    private Context unityContext;
    IUnityBufferFetcher unityFetcher;

/// ________________________ Bytes buffer ________________________

// 64kb is max allowed size for baos
    private /*volatile*/ final ByteArrayOutputStream baosBuffer = new ByteArrayOutputStream(64 * 1024); // default capacity 32 bytes
    // private volatile ByteBuffer readBuffer;

    private final ReentrantLock reentrantLock = new ReentrantLock();

///====================================== Constructor ======================================

    public SerialPortMediator(Context unityActivity, IUnityBufferFetcher fetcher){
        this.unityContext = unityActivity;
        this.unityFetcher = fetcher;
        DebugUnity("Constructed, is unityFetcher available: " + (unityFetcher!=null));
    }

///====================================== for unity ======================================
// pure copies of old unity's script

    public boolean TryConnect(){
//        Thread initThread = new Thread(() -> {
//            InitializeSerial();
//            DebugUnity("Current Thread: " + Thread.currentThread());
//        });
//        initThread.start();

        try{
            CompletableFuture.runAsync(() -> {
                InitializeSerial();
                DebugUnity("[TryConnect.CompletableFuture]Current Thread: " + Thread.currentThread());
            });

            if(usbManager == null){
                DebugUnity("usbManager == null");
                return false;
            }

            return true;
        }
        catch(Exception e){
            Log.e("SERIAL PORT ERROR", "CONNECT BY NUMER" + e.getMessage());
            DebugUnity("Error during TryConnect: " + e.getMessage());
            onRunError(e);
            return false;
        }
    }

    public void CleanupConnection(Context context){
        //
        if(isDeviceConnected){
            if(serialIoManager != null){
                // stop
                serialIoManager = null;
            }
            if(usbSerialPort != null){
                // close
                usbSerialPort = null;
            }

            try{
                StopFlushLoop();
            }
            catch(Exception e){
                DebugUnity("Cannot stop flush loop clearly");
            }
            finally {
                isDeviceConnected = false;
                DebugUnity("SUCCESSFULLY DISCONNECTED");
            }
            if(permissionReceiver != null){
                DebugUnity("Somehow permission receiver not null");
                try{
                    // context.unregisterReceiver(permissionReceiver);
                    unityContext.unregisterReceiver(permissionReceiver);
                }
                catch(Exception e){
                    DebugUnity("Cannot unregister permissionReceiver clearly");
                }
            }
            else{
                DebugUnity("Permission receiver was null");
            }
        }
        else {
            DebugUnity("Not connected");
        }
    }

    public void SendBytes(byte[] bytes){
        if(!isDeviceConnected) {
            DebugUnity("Cannot write, not connected");
            return;
        }
        if(usbSerialPort == null){
            DebugUnity("Cannot write, no serial port");
            return;
        }
        try
        {
            DebugUnity("Trying to send data");
            usbSerialPort.write(bytes, writeTimeout);
        }
        catch (Exception e) {
            DebugUnity("[SendBytes] " + e.getMessage());
            onRunError(e);
        }
    }

    // async !
    public void InitializeSerial(){
//            Thread initThread = new Thread(() ->{
//                DebugUnity("Current Thread: " + Thread.currentThread());
//                InitialThreadFilling();
//            });
//            initThread.start();
        try{
            DebugUnity("[InitializeSerial] Current Thread: " + Thread.currentThread());
            DebugUnity("[InitializeSerial] Current ThreadGroup: " + Objects.requireNonNull(Thread.currentThread().getThreadGroup()).getName());
            DebugUnity("[InitializeSerial] Current ThreadGroup: " + Thread.currentThread().getState().name());
            InitialThreadFilling();
        }
        catch (Exception e) {
            DebugUnity("[InitializeSerial] " + e.getMessage());
            onRunError(e);
        }
    }

    public void ConnectByNumer(int num){
        try{
            if(availableDrivers != null){
                driver = availableDrivers.get(num);
                usbDevice = driver.getDevice();

//            Thread connectSerial = new Thread(() ->{
//                DebugUnity("Current Thread: " + Thread.currentThread());
//                ConnectSerial(unity);
//            });
//            connectSerial.start();

                CompletableFuture.runAsync(() ->{
                    DebugUnity("[ConnectByNumer] Current Thread: " + Thread.currentThread());
                    ConnectSerial();
                });

                ConnectToDevice(driver);
            }
            else{
                DebugUnity("Serial is not initialized yet");
            }
        }
        catch(Exception e){
            Log.e("SERIAL PORT ERROR", "[ConnectByNumer] " + e.getMessage());
            DebugUnity("[InitialAsync] " + e.getMessage());
            onRunError(e);
        }
    }

///====================================== Privates for unity ======================================

    public CompletableFuture<Void> InitialAsync() {
//        return CompletableFuture.supplyAsync(() -> {
//            //
//            // return null;
//        });

        return CompletableFuture.runAsync(() -> {
            try {
                usbManager = (UsbManager) unityContext.getSystemService(Context.USB_SERVICE);
                UsbSerialProber prober = UsbSerialProber.getDefaultProber();
                availableDrivers = prober.findAllDrivers(usbManager);
                availablePortCount = availableDrivers.size();
                if(availablePortCount == 0){
                    DebugUnity("No serial driver available");
                }
                else{
                    DebugUnity("Serial driver available");
                }
            }
            catch (Exception e) {
                DebugUnity("[InitialAsync] " + e.getMessage());
                onRunError(e);
            }
        });

//api > 31
//        try{
//
//
//            return CompletableFuture.completedFuture(null);
//        }
//        catch(Exception e){
//
//
//            return CompletableFuture.failedFuture(e);
//        }
    }

    // CompletableFuture.isDone() // - проверка работает ли метод

    private void InitialThreadFilling(){
        try{
            // usbManager, prober, drivers. count, names
            usbManager = (UsbManager) unityContext.getSystemService(Context.USB_SERVICE);
            UsbSerialProber prober = UsbSerialProber.getDefaultProber();
            availableDrivers = prober.findAllDrivers(usbManager);
            availablePortCount = availableDrivers.size();
            if(availablePortCount == 0){
                DebugUnity("No serial driver available");
            }
            else{
                DebugUnity("Serial driver available");
            }
        }
        catch (Exception e) {
            DebugUnity("[InitialThreadFilling] " + e.getMessage());
            onRunError(e);
        }
    }

    private void ConnectSerial(){
        // ask hasPermission async
        try{
            if(usbManager == null || usbDevice == null){
                DebugUnity("Manager is null");
                // yield return null; // wait approximately 30ms
            }
            while(!usbManager.hasPermission(usbDevice)){
                RequestUsbPermission();
                DebugUnity("Asking permission in cycle");
                Thread.sleep(1000);
                // yield return new WaitForSeconds(1);
            }
        }
        catch (Exception e) {
            Log.e("SERIAL PORT ERROR", "[ConnectSerial]" + e.getMessage());
            DebugUnity("[ConnectSerial] " + e.getMessage());
            onRunError(e);
        }
    }

    private void RequestUsbPermission(){
        try{
            Intent intent = new Intent(k_usbPermission);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    unityContext, 0, intent, PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(usbDevice, pendingIntent);
        }
        catch(Exception e){
            DebugUnity("Error requesting permissions: " + e.getMessage());
        }
    }

    private void ConnectToDevice(UsbSerialDriver driver){
        try{
            UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
            if(usbManager == null){
                DebugUnity("usbManager is null");
                return;
            }
            if(usbDevice == null){
                DebugUnity("usbDevice is null");
                return;
            }
            if(connection == null){
                DebugUnity("USB connection is null");
                return;
            }
            List<UsbSerialPort> ports = driver.getPorts();
            usbSerialPort = ports.get(0);

            usbSerialPort.open(connection);
            usbSerialPort.setParameters(baudRate, 8,1, UsbSerialPort.PARITY_NONE);

            InitializeIoManager();

            isDeviceConnected = true;

            StartFlushLoop();

            DebugUnity("USB connection established");
        }
        catch(Exception e){
            Log.e("SERIAL PORT ERROR", "[ConnectToDevice] " + e.getMessage());
            DebugUnity("[ConnectToDevice] " + e.getMessage());
            onRunError(e);
        }
    }

    private void InitializeIoManager(){
        serialIoManager = new SerialInputOutputManager(usbSerialPort, this);
        serialIoManager.start();
    }


///====================================== Fetcher ======================================

    private Thread loopThread;

    public void StartFlushLoop(){
//        if(loopThread != null && loopThread.isAlive()){
//            DebugUnity("Flushing thread already running");
//            return;
//        }
        if(isDeviceConnected){
//            loopThread = new Thread(() -> {
//                try{
//                    DebugUnity("Start flushing loop");
//                    FlushLoopa();
//                }
//                catch (InterruptedException e) {
//                    DebugUnity("InterruptedException during flushing data");
//                    onRunError(e);
//                }
//            });
//            loopThread.start();


            DebugUnity("[StartFlushLoop] Current Thread: " + Thread.currentThread());
            //not another thread but async
            CompletableFuture.runAsync(() ->{
                try{
                    DebugUnity("Start flushing loop");
                    DebugUnity("[StartFlushLoop.CompletableFuture] Current Thread: " + Thread.currentThread());
                    FlushLoopa();
                }
                catch (InterruptedException e) {
                    DebugUnity("InterruptedException during flushing data");
                    onRunError(e);
                }
            });


        }

        // CompletableFuture.delayedExecutor
    }


    // @Asynchronous // cannot resolve
    public Future<Void> FlushLoop(){

        return null;
    }
private boolean checkBufferDelay = true;
    public void FlushLoopa() throws InterruptedException {
        while(isDeviceConnected){
            reentrantLock.lock();
            try{
                if(baosBuffer.size() > 0){
                    if(baosBuffer.size() >= (64000)){
                        DebugUnity("Buffer is almost 64 KB !!! stop!");
                        //baosBuffer.reset();
                    }
//                    byte[] snapshot = ;
                    unityFetcher.FlushData(/*snapshot*/ baosBuffer.toByteArray());
                    baosBuffer.reset();
                }
//                else{
                    // DebugUnity("Buffer is empty");
//                }
            }
            catch (Exception e) {
                DebugUnity("Error during flushing data on java side");
            }
            finally{
                reentrantLock.unlock();
            }
            if(!isDeviceConnected){
                DebugUnity("Disconnect, so flush loop closed");
                break;
            }
            if(checkBufferDelay){
                checkBufferDelay = false;
                DebugUnity("now readBufferDelay is: " + readBufferDelay);
            }
            Thread.sleep(readBufferDelay);
        }
    }

    //
//    Handler mHandler = new Handler();
//
//    new Thread(new Runnable() {
//        @Override
//        public void run () {
//            // Perform long-running task here
//            // (like audio buffering).
//            // You may want to update a progress
//            // bar every second, so use a handler:
//        // обращение к юай потоку
//            mHandler.post(new Runnable() {
//                @Override
//                public void run () {
//                    // make operation on the UI - for example
//                    // on a progress bar.
//                }
//            });
//        }
//    }).start();

    private void StopFlushLoop(){
        if(!isDeviceConnected){

            // CompletableFuture.isDone() // - проверка работает ли метод

//            try{
//                loopThread.join(readBufferDelay);
//            }
//            catch (InterruptedException e) {
//                DebugUnity("InterruptedException during joining flush loop");
//                onRunError(e);
//            }
//            finally {
//                if(loopThread.isAlive()){
//                    loopThread.interrupt();
//                }
//                // loopThread = null; // чтобы переопределить при новом подключении - будут проблемы из >=2 потока
//            }
        }
        else{
            isDeviceConnected = false;
            StopFlushLoop();
        }

    }


///====================================== Detach Receiver ======================================

//public static class DetachReceiver extends BroadcastReceiver{
//
//    SerialPortMediator mediatorInstance;
//
//    public DetachReceiver(SerialPortMediator a){
//        mediatorInstance = a;
//    }
//
//    @Override
//    public void onReceive(Context context, Intent intent) {
//        Toast.makeText(context, "received detach", Toast.LENGTH_LONG).show();
//        try {
//            if(intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)){
//                if(mediatorInstance != null){
//                    mediatorInstance.DebugUnity("Usb detached");
//                    if(mediatorInstance.isDeviceConnected)
//                        if(mediatorInstance.unityContext.equals(context)){
//                            mediatorInstance.DebugUnity("contecsts equals");
//                            mediatorInstance.CleanupConnection(context);
//                        }
//                        else{
//                            mediatorInstance.DebugUnity("contecsts not equals");
//                            mediatorInstance.CleanupConnection(mediatorInstance.unityContext);
//                        }
//                }
//
//            }
//        } catch (Exception e) {
//            Toast.makeText(context, e.getMessage(), Toast.LENGTH_LONG).show();
//            Log.e("[SERIALPORTLIB]", e.getMessage(), e);
//            //throw new RuntimeException(e);
//        }
//    }
//}
//
//
//    public DetachReceiver receiver = new DetachReceiver(this);


/// ====================================== getters & setters ======================================

    @Override
    public String GetPortName() {
        return driver.getDevice().getDeviceName();
    }

    /**
     * @return
     */
    @Override
    public String[] GetPortsNames() {
        String[] names = new String[availablePortCount];
        for(int i = 0; i < availablePortCount; i++){
            names[i] = availableDrivers.get(i).getDevice().getDeviceName();
        }
        return names;
    }

    /**
     * @return
     */
    @Override
    public int GetPortsCount() {
        return availablePortCount;
    }

    /**
     * @return
     */
    @Override
    public boolean GetIsConnected() {
        return isDeviceConnected;
    }

    /**
     * @return
     */
    @Override
    public int GetReadBufferDelay() {
        return readBufferDelay;
    }

    /**
     * @return
     */
    @Override
    public int GetReadTimeout() {
        return readTimeout;
    }

    /**
     * @return
     */
    @Override
    public int GetWriteTimeout() {
        return writeTimeout;
    }

    /**
     * @return
     */
    @Override
    public int GetBaudRate() {
        return baudRate;
    }

    /**
     * @param delay
     */
    @Override
    public void SetReadBufferDelay(int delay) {
        if(delay > 0){
            checkBufferDelay = true;
            readBufferDelay = delay;
        }
        else{
            DebugUnity("Attempt to set unallowed readBufferDelay. Current is: " + readBufferDelay);
        }
    }

    /**
     * @param timeout
     */
    @Override
    public void SetReadTimeout(int timeout) {
        if(timeout > 0){
            readTimeout = timeout;
        }
        else{
            DebugUnity("Unallowed read timeout, current " + readTimeout);
        }
    }

    /**
     * @param timeout
     */
    @Override
    public void SetWriteTimeout(int timeout) {
        if(timeout > 0){
            writeTimeout = timeout;
        }
        else{
            DebugUnity("Unallowed write timeout, current " + writeTimeout);
        }
    }

    /**
     *
     */
    public void SetBaudRate(int newBR){
        if (newBR <= 0)
        {
            DebugUnity("Entered invalid baud rate. Current is " + baudRate);
        }
        else
        {
            baudRate = newBR;
        }
    }

    /**
     *
     */
    @Override
    public void SetIsDebug(boolean debug) {
        isDebug = debug;
    }

///====================================== SerialInputOutputManager.Listener ======================================

    /**
     * Called when new incoming data is available.
     *
     * @param data
     */
    @Override
    public void onNewData(byte[] data) {
        reentrantLock.lock();
        try{
            baosBuffer.write(data, 0, data.length);

        }
        catch(Exception e){
            DebugUnity("[onNewData] " + e.getMessage());
            onRunError(e);
        }
        finally {
            reentrantLock.unlock();
        }
    }

    /**
     * Called when  SerialInputOutputManager.runRead() or SerialInputOutputManager.runWrite() aborts due to an error.
     *
     * @param e
     */
    @Override
    public void onRunError(Exception e) {
        SendMessageToUnity("[runRead/runWrite ERROR] " + e.getMessage());
    }


///====================================== for unity ======================================

    private static void SendMessageToUnity(String msg){
        UnityPlayer.UnitySendMessage("Script_UI", "OnBroadcastReceived", msg);
        // Base64.encodeToString(data, Base64.DEFAULT)); // если надо будет отправлять байты
    }

    private void DebugUnity(String msg){
        if(isDebug){
            UnityPlayer.UnitySendMessage("Script_UI", "OnBroadcastReceived", msg);
        }
    }


///====================================== Debug bytes ======================================

    private void debugBitOfData(byte[] data){
        int debugLen  = 15;
        if(15 > data.length) debugLen = data.length;

        DebugUnity("DebugLen:"  + debugLen);
        if(debugLen > 0){
            byte[] debug = Arrays.copyOfRange(data, 0, debugLen);
            String debugS = Base64.encodeToString(debug, Base64.DEFAULT);

            DebugUnity("debug bytes: " + debugS);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;

        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

}
