package com.hoho.android.usbserial.unityplugin;

public class UnityMessageHandler_Reference {

    public interface Callback {
        void onMessage(String message);
    }

    private static Callback unityCallback;

    public static void registerCallback(Callback callback) {
        unityCallback = callback;
    }

    public static void triggerEvent() {
        if (unityCallback != null) {
            unityCallback.onMessage("Test handled message from Java c==3");
        }
    }

}
