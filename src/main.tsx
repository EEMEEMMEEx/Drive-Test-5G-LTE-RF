import React from 'react';
import ReactDOM from 'react-dom/client';
import { Capacitor } from '@capacitor/core';
import App from './App';
import { PwaControls } from './pwa/PwaControls';
import './index.css';

const isNativePlatform =
  typeof Capacitor !== 'undefined' &&
  typeof Capacitor.isNativePlatform === 'function' &&
  Capacitor.isNativePlatform();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
    {/* Service worker + install prompt only on the web PWA, never in the Capacitor WebView */}
    {!isNativePlatform && <PwaControls />}
  </React.StrictMode>
);
