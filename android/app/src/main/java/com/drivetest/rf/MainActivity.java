package com.drivetest.rf;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DriveTestPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
