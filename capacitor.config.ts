import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.drivetest.rf',
  appName: 'Drive Test 5G LTE RF',
  webDir: 'dist',
  server: {
    androidScheme: 'https'
  }
};

export default config;
