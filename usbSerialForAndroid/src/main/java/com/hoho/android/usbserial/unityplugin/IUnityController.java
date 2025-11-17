package com.hoho.android.usbserial.unityplugin;

import android.content.Context;

public interface IUnityController {
    // для настроек usb
//    byte[] FlushData();

///====================================== for unity ======================================

    boolean TryConnect(Context context);
    void CleanupConnection(/*Context context*/);
    void SendBytes(byte[] bytes);
    void InitializeSerial(Context context);
    void ConnectByNumer(int num);

///====================================== getters & setters ======================================

    String GetPortName();
    String[] GetPortsNames();
    int GetPortsCount();
    boolean GetIsConnected();
    int GetReadBufferDelay();
    int GetReadTimeout();
    int GetWriteTimeout();
    int GetBaudRate();


    void SetReadBufferDelay(int delay);
    void SetReadTimeout(int timeout);
    void SetWriteTimeout(int timeout);
    void SetBaudRate(int baudRate);
    void SetIsDebug(boolean debug);


    /*
    private readonly ISerialInputOutputManagerListener Listener; // unity exemplar

     public SerialInputOutputManagerListenerProxy(ISerialInputOutputManagerListener listener)
        : base("com.hoho.android.usbserial.unityplugin.IUnityController" - класс с которым будт взаимодействовать наш прокси)
    {
        Listener = listener;
    }
    * */

}
