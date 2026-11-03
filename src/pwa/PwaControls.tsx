import { useEffect, useState } from 'react';
import { Capacitor } from '@capacitor/core';
import { useRegisterSW } from 'virtual:pwa-register/react';
import { Activity, Download, RefreshCw, X } from 'lucide-react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
}

/**
 * PWA controls mounted only on the web (never inside the Capacitor WebView).
 *
 * Responsibilities:
 *  - Register the Service Worker via vite-plugin-pwa's virtual module.
 *  - "Ready for offline use" toast when the SW finishes installing.
 *  - "New version available" prompt when a SW update is waiting; reload to apply.
 *  - Capture `beforeinstallprompt` and render an install button for Chromium.
 *
 * The virtual module is a no-op in `vite dev` (devOptions.enabled = false), so
 * this component only registers the worker in production builds.
 */
export function PwaControls() {
  const {
    needRefresh: [needRefresh, setNeedRefresh],
    offlineReady: [offlineReady, setOfflineReady],
    updateServiceWorker,
  } = useRegisterSW({
    onRegisterError(error) {
      console.error('Service worker registration error', error);
    },
  });

  const [deferredPrompt, setDeferredPrompt] = useState<BeforeInstallPromptEvent | null>(null);

  useEffect(() => {
    // Extra safety: never attach install prompts inside the native wrapper.
    if (typeof Capacitor !== 'undefined' && Capacitor.isNativePlatform?.()) {
      return;
    }
    const onPrompt = (event: Event) => {
      event.preventDefault();
      setDeferredPrompt(event as BeforeInstallPromptEvent);
    };
    const onInstalled = () => {
      setDeferredPrompt(null);
    };
    window.addEventListener('beforeinstallprompt', onPrompt);
    window.addEventListener('appinstalled', onInstalled);
    return () => {
      window.removeEventListener('beforeinstallprompt', onPrompt);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, []);

  const handleInstall = async () => {
    if (!deferredPrompt) return;
    deferredPrompt.prompt();
    try {
      await deferredPrompt.userChoice;
    } catch {
      // userChoice can reject if the prompt is dismissed/unsupported
    } finally {
      setDeferredPrompt(null);
    }
  };

  const handleUpdate = () => {
    // updateServiceWorker(true) -> messageSkipWaiting(); the client's
    // "controlling" listener then reloads the page to apply the update.
    setNeedRefresh(false);
    void updateServiceWorker();
  };

  return (
    <>
      {deferredPrompt && (
        <div className="fixed bottom-20 inset-x-0 z-[9999] flex justify-center px-4 pointer-events-none">
          <div className="rf-pwa-install pointer-events-auto flex items-center gap-3 rounded-xl border border-border bg-surface-elevated px-4 py-3 shadow-lg">
            <Activity className="h-5 w-5 text-accent shrink-0" aria-hidden />
            <div className="text-sm">
              <p className="font-medium text-text">Install RF Field Suite</p>
              <p className="text-xs text-text-muted">Add to home screen &amp; use offline.</p>
            </div>
            <button
              type="button"
              onClick={handleInstall}
              className="inline-flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-sm font-medium text-on-accent hover:opacity-90 shrink-0"
            >
              <Download className="h-4 w-4" aria-hidden />
              Install
            </button>
            <button
              type="button"
              onClick={() => setDeferredPrompt(null)}
              className="rounded-md p-1 text-text-muted hover:text-text shrink-0"
              aria-label="Dismiss install prompt"
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          </div>
        </div>
      )}

      {needRefresh && (
        <div className="fixed bottom-20 inset-x-0 z-[9999] flex justify-center px-4 pointer-events-none">
          <div className="pointer-events-auto flex items-center gap-3 rounded-xl border border-border bg-surface-elevated px-4 py-3 shadow-lg">
            <RefreshCw className="h-5 w-5 text-accent shrink-0" aria-hidden />
            <div className="text-sm">
              <p className="font-medium text-text">New version available</p>
              <p className="text-xs text-text-muted">Reload to apply the latest update.</p>
            </div>
            <button
              type="button"
              onClick={handleUpdate}
              className="inline-flex items-center gap-1.5 rounded-lg bg-accent px-3 py-1.5 text-sm font-medium text-on-accent hover:opacity-90 shrink-0"
            >
              <RefreshCw className="h-4 w-4" aria-hidden />
              Reload
            </button>
          </div>
        </div>
      )}

      {offlineReady && (
        <div className="fixed bottom-20 inset-x-0 z-[9999] flex justify-center px-4 pointer-events-none">
          <div className="pointer-events-auto flex items-center gap-3 rounded-xl border border-border bg-surface-elevated px-4 py-3 shadow-lg">
            <span className="h-2.5 w-2.5 rounded-full bg-success" aria-hidden />
            <p className="text-sm font-medium text-text">Ready for offline use</p>
            <button
              type="button"
              onClick={() => setOfflineReady(false)}
              className="rounded-md p-1 text-text-muted hover:text-text shrink-0"
              aria-label="Dismiss"
            >
              <X className="h-4 w-4" aria-hidden />
            </button>
          </div>
        </div>
      )}
    </>
  );
}
