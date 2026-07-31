package com.smartbalanco.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Plugin próprio: deixa o app pedir o redesenho do widget assim que os
        // dados mudam, em vez de esperar o ciclo de 30 min do Android.
        registerPlugin(WidgetPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
