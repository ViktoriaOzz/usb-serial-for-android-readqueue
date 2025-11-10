package com.hoho.android.usbserial.unityplugin;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Base64;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.unity3d.player.UnityPlayer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReentrantLock;

public class SerialPortMediator implements IUnityController, com.hoho.android.usbserial.util.SerialInputOutputManager.Listener {


/// ________________________ Entities (from old script) ________________________

    private UsbManager usbManager;
    private UsbDevice usbDevice;
    private UsbSerialPort usbSerialPort;
    private SerialInputOutputManager serialIoManager;
    private BroadcastReceiver permissionReceiver;
    private UsbSerialDriver driver;

    private List<UsbSerialDriver> availableDrivers;

    private int availablePortCount = 0;


    /// ________________________ Entities from Unity ________________________

    private Context context;
    private IUnityController unity;

/// ________________________ parameters ________________________

    private int baudRate = 460800;
    private static String k_usbPermission = "com.example.app.USB_PERMISSION";
    private boolean isDeviceConnected = false;
    private boolean isDebug = true;
    private int readTimeout = 5; // ms
    private int writeTimeout = 100;
    private int readBufferDelay = 40;

/// ________________________ Bytes buffer ________________________

    IUnityBufferFetcher unityFetcher;

    private /*volatile*/ final ByteArrayOutputStream baosBuffer = new ByteArrayOutputStream(1024); // default capacity 32 bytes
    // private volatile ByteBuffer readBuffer;

    private final ReentrantLock reentrantLock = new ReentrantLock();

///====================================== Constructor ======================================

    public SerialPortMediator(Context unityActivity){
        this.context = unityActivity;
        DebugUnity("Constructed");
    }

///====================================== for unity ======================================
// pure copies of old unity's script

    public boolean TryConnect(){
//        Thread initThread = new Thread(() -> {
//            InitializeSerial();
//            DebugUnity("Current Thread: " + Thread.currentThread());
//        });
//        initThread.start();

        CompletableFuture.runAsync(() -> {
            InitializeSerial();
            DebugUnity("Current Thread: " + Thread.currentThread());
        });

        if(usbManager == null){
            DebugUnity("usbManager == null");
            return false;
        }

        return true;
    }

    public void CleanupConnection(Context context){
        //
        if(isDeviceConnected){
            try{
                if(serialIoManager != null){
                    // stop
                    serialIoManager = null;
                }
                if(usbSerialPort != null){
                    // close
                    usbSerialPort = null;
                }
            }
            catch(Exception e){
                DebugUnity("Cannot close clearly");
            }

            if(permissionReceiver != null){
                try{
                    context.unregisterReceiver(permissionReceiver);
                }
                catch(Exception e){
                    // skip
                }
            }
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
        catch (Exception e)
        {
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
        InitialThreadFilling();
    }

    // put proxy
    public void ConnectByNumer(int num, IUnityController unity){
        this.unity = unity;
        if(this.unity == null){
            DebugUnity("UnityListener is null");
            return;
        }
        if(availableDrivers != null){
            driver = availableDrivers.get(num);
            usbDevice = driver.getDevice();

//            Thread connectSerial = new Thread(() ->{
//                DebugUnity("Current Thread: " + Thread.currentThread());
//                ConnectSerial(unity);
//            });
//            connectSerial.start();

            CompletableFuture.runAsync(() ->{
                DebugUnity("Current Thread: " + Thread.currentThread());
                ConnectSerial(unity);
            });

            ConnectToDevice(driver);
        }
        else{
            DebugUnity("Serial is not initialized yet");
        }
    }

///====================================== Privates for unity ======================================

    private void InitialThreadFilling(){
        try{
            // usbManager, prober, drivers. count, names
            usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbSerialProber prober = UsbSerialProber.getDefaultProber();
            availableDrivers = prober.findAllDrivers(usbManager);
            availablePortCount = availableDrivers.size();
            if(availablePortCount == 0){
                DebugUnity("No serial driver available");
            }
            else{

            }
        }
        catch (Exception e) {
            onRunError(e);
        }
    }

    private void ConnectSerial(IUnityController unity){
        // ask hasPermission async
        try{
            if(usbManager == null || usbDevice == null){
                DebugUnity("Manager is null");
                // yield return null; // wait approximately 30ms
            }
            while(!usbManager.hasPermission(usbDevice)){
                RequestUsbPermission();
                // yield return new WaitForSeconds(1);
            }
        }
        catch (Exception e) {
            // skip
        }
    }

    private void RequestUsbPermission(){
        try{
            Intent intent = new Intent(k_usbPermission);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(usbDevice, pendingIntent);
        }
        catch(Exception e){
            DebugUnity("Error requesting permissions: " + e.getMessage());
        }
    }

    private void ConnectToDevice(UsbSerialDriver driver){
        try{
            UsbDeviceConnection connection = usbManager.openDevice(usbDevice);
            if(connection == null){
                DebugUnity("Unable to open USB connection");
                return;
            }
            List<UsbSerialPort> ports = driver.getPorts();
            usbSerialPort = ports.get(0);

            usbSerialPort.open(connection);
            usbSerialPort.setParameters(baudRate, 8,1, UsbSerialPort.PARITY_NONE);

            InitializeIoManager();

            isDeviceConnected = true;
            DebugUnity("USB connection established");
        }
        catch(Exception e){
            onRunError(e);
        }
    }

    private void InitializeIoManager(){
        serialIoManager = new SerialInputOutputManager(usbSerialPort, this);
        serialIoManager.start();
    }


///====================================== Privates ======================================




///====================================== for unity ======================================


//    /**
//     *
//     */
//    @Override
//    public void FlushData(){
//
//        // currentActivity.runOnUiThread(new Runnable () {..});
//
//        reentrantLock.lock();
//        try{
//            byte[] result = new byte[baosBuffer.size()];
//            result = baosBuffer.toByteArray();
//            baosBuffer.reset();
//
//            if(isDebug){
//                DebugUnity("Pack len: " + result.length);
//                debugBitOfData(result);
//            }
//
//            return result;
//        }
//        catch (Exception e) {
//            onRunError(e);
//            return null;
//        }
//        finally {
//            reentrantLock.unlock();
//        }
//    }


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
        return readTimeout;
    }

    /**
     * @return
     */
    @Override
    public int GetWriteBufferDelay() {
        return 0;
    }

    /**
     * @return
     */
    @Override
    public int GetBaudRate() {
        return 0;
    }

    /**
     * @param delay
     */
    @Override
    public void SetReadBufferDelay(int delay) {
        if(delay > 0){
            readBufferDelay = delay;
        }
        else{
            DebugUnity("Attempt to set unallowed readBufferDelay: " + delay);
        }
    }

    /**
     * @param timeout
     */
    @Override
    public void SetReadTimeout(int timeout) {

    }

    /**
     * @param timeout
     */
    @Override
    public void SetWriteTimeout(int timeout) {

    }

    /**
     *
     */
    public void SetBaudRate(int newBR){
        if (newBR <= 0)
        {
            DebugUnity("Entered invalid baud rate. Default is 460800");
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
        DebugUnity("onNewData entrance !");

        reentrantLock.lock();
        try{
            baosBuffer.write(data, 0, data.length);

        }
        catch(Exception e){
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
        UnityPlayer.UnitySendMessage("Script_UAndReceive", "OnBroadcastReceived", msg);
        // Base64.encodeToString(data, Base64.DEFAULT)); // если надо будет отправлять байты
    }

    private void DebugUnity(String msg){
        if(isDebug){
            UnityPlayer.UnitySendMessage("Script_UAndReceive", "OnBroadcastReceived", msg);
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
