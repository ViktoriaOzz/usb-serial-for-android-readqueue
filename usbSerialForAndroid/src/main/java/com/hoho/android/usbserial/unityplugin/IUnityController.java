package com.hoho.android.usbserial.unityplugin;

import android.content.Context;

public interface IUnityController {
    // из юнити создаем AndroidJavaProxy реализующ этот интерфейс
    // на Flush делаем вывод в юнитевский контейнер
//    byte[] FlushData();

///====================================== for unity ======================================

    boolean TryConnect();
    void CleanupConnection(Context context);
    void SendBytes(byte[] bytes);
    void InitializeSerial();
    void ConnectByNumer(int num, IUnityController unity);

///====================================== getters & setters ======================================

    String GetPortName();
    String[] GetPortsNames();
    int GetPortsCount();
    boolean GetIsConnected();
    int GetReadBufferDelay();
    int GetWriteBufferDelay();
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
