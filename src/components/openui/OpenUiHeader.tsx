import React, { useState, useEffect } from 'react';
import {
  Activity,
  BarChart3,
  Compass,
  FolderKanban,
  Globe,
  HelpCircle,
  LayoutGrid,
  Menu,
  Moon,
  Radio,
  Sun,
  X,
} from 'lucide-react';
import type { MainNavTab } from '../../types/drivetest';
import type { SupportedLocale, TranslationDictionary } from '../../i18n/translations';

export interface OpenUiHeaderProps {
  appTitle: string;
  appSubtitle: string;
  appVersion: string;
  activeTab: MainNavTab;
  onSelectTab: (tab: MainNavTab) => void;
  isRunning?: boolean;
  onOpenWidgets: () => void;
  onReplayTutorial: () => void;
  locale: SupportedLocale;
  onSelectLocale: (locale: SupportedLocale) => void;
  lightTheme: boolean;
  onToggleTheme: () => void;
  t: TranslationDictionary;
}

export const OpenUiHeader: React.FC<OpenUiHeaderProps> = ({
  appTitle,
  appSubtitle,
  appVersion,
  activeTab,
  onSelectTab,
  isRunning = false,
  onOpenWidgets,
  onReplayTutorial,
  locale,
  onSelectLocale,
  lightTheme,
  onToggleTheme,
  t,
}) => {
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);

  // Close mobile drawer on Escape key press
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isMobileMenuOpen) {
        setIsMobileMenuOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isMobileMenuOpen]);

  const navItems = [
    { id: 'DASHBOARD' as const, label: t.navDashboard, icon: Activity, desc: 'Real-time RF Telemetry & Drive Control' },
    { id: 'MAP' as const, label: t.navMap, icon: Compass, desc: 'GPS Trajectory & Sector Site Mapping' },
    { id: 'CAMPAIGNS' as const, label: t.navCampaigns, icon: FolderKanban, desc: 'Drive Session Logs & Acceptance Reports' },
    { id: 'ANALYTICS' as const, label: t.navAnalytics, icon: BarChart3, desc: 'CDF Curves, Diagnostics & RF Correlation' },
  ];

  const handleMobileNavSelect = (id: MainNavTab) => {
    onSelectTab(id);
    setIsMobileMenuOpen(false);
  };

  return (
    <header className="sticky top-0 z-30 bg-surface/95 backdrop-blur-md border-b border-border shadow-xs">
      <div className="max-w-7xl mx-auto px-3 sm:px-4 py-2.5 sm:py-3 flex items-center justify-between gap-2 sm:gap-4">
        {/* Left: Brand Identity & Telemetry Indicator */}
        <div className="flex items-center gap-2.5 sm:gap-3 shrink-0">
          <div className="relative w-9 h-9 sm:w-10 sm:h-10 rounded-xl bg-accent/15 border border-accent/40 flex items-center justify-center text-accent shadow-xs">
            <Radio className="w-5 h-5" />
            {isRunning && (
              <span className="absolute -top-1 -right-1 flex h-3 w-3">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent opacity-75" />
                <span className="relative inline-flex rounded-full h-3 w-3 bg-accent" />
              </span>
            )}
          </div>
          <div>
            <div className="flex items-center gap-1.5 sm:gap-2">
              <h1 className="text-sm sm:text-base font-bold tracking-tight text-text leading-tight">
                {appTitle}
              </h1>
              <span className="text-[10px] sm:text-[11px] font-mono px-1.5 sm:px-2 py-0.5 rounded-full bg-surface-elevated border border-border text-accent font-semibold">
                v{appVersion}
              </span>
            </div>
            <p className="text-[11px] sm:text-xs text-text-muted hidden sm:block truncate max-w-[240px] md:max-w-xs lg:max-w-md">
              {appSubtitle}
            </p>
          </div>
        </div>

        {/* Center: Desktop Navigation Tabs (hidden on mobile) */}
        <nav
          aria-label="Main navigation"
          className="hidden md:flex items-center gap-1 bg-surface-elevated/90 border border-border rounded-xl p-1 shadow-xs"
        >
          {navItems.map((item) => {
            const Icon = item.icon;
            const isActive = activeTab === item.id;
            const isLiveMonitor = item.id === 'DASHBOARD';

            return (
              <button
                key={item.id}
                type="button"
                onClick={() => onSelectTab(item.id)}
                aria-current={isActive ? 'page' : undefined}
                className={`relative px-3.5 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 transition-all min-h-[38px] ${
                  isActive
                    ? 'bg-accent text-on-accent shadow-xs'
                    : 'text-text-muted hover:text-text hover:bg-surface/50'
                }`}
              >
                <Icon className="w-4 h-4 shrink-0" />
                <span>{item.label}</span>
                {isLiveMonitor && isRunning && (
                  <span className={`w-2 h-2 rounded-full ${isActive ? 'bg-on-accent animate-pulse' : 'bg-accent animate-ping'}`} />
                )}
              </button>
            );
          })}
        </nav>

        {/* Right: Controls & Utilities */}
        <div className="flex items-center gap-1.5 sm:gap-2">
          {/* Desktop-only quick action buttons */}
          <div className="hidden md:flex items-center gap-2">
            <button
              type="button"
              onClick={onOpenWidgets}
              title={t.widgetsPreview}
              className="px-3 py-2 rounded-lg bg-surface-elevated border border-border text-xs font-semibold text-text hover:border-accent hover:text-accent flex items-center gap-1.5 transition shadow-xs min-h-[38px]"
            >
              <LayoutGrid className="w-4 h-4 text-accent" />
              <span>{t.widgetsPreview}</span>
            </button>

            <button
              type="button"
              onClick={onReplayTutorial}
              title="Replay Spotlight Tour"
              aria-label="Interactive Guide - Replay tour"
              className="px-3 py-2 rounded-lg bg-surface-elevated border border-border text-xs font-semibold text-text hover:border-accent hover:text-accent flex items-center gap-1.5 transition shadow-xs min-h-[38px]"
            >
              <HelpCircle className="w-4 h-4 text-accent" />
              <span>{t.replayTutorial}</span>
            </button>
          </div>

          {/* Language Selector (Universal: Desktop & Mobile) */}
          <div className="flex items-center gap-1 sm:gap-1.5 bg-surface-elevated border border-border rounded-lg px-2 sm:px-2.5 py-1.5 hover:border-accent/50 transition min-h-[38px] sm:min-h-[38px] min-w-[40px]">
            <Globe className="w-4 h-4 text-accent shrink-0" />
            <select
              aria-label="Select interface language"
              value={locale}
              onChange={(e) => onSelectLocale(e.target.value as SupportedLocale)}
              className="bg-surface-elevated text-xs font-semibold text-text focus:outline-none cursor-pointer py-0.5"
            >
              <option value="en" className="bg-surface-elevated text-text font-semibold">
                EN
              </option>
              <option value="th" className="bg-surface-elevated text-text font-semibold">
                TH
              </option>
            </select>
          </div>

          {/* Theme Toggle (Universal: Desktop & Mobile) */}
          <button
            type="button"
            aria-label="Toggle color theme"
            onClick={onToggleTheme}
            className="p-2 rounded-lg bg-surface-elevated border border-border text-text-muted hover:text-accent hover:border-accent transition min-h-[38px] min-w-[38px] flex items-center justify-center shadow-xs"
          >
            {lightTheme ? (
              <Moon className="w-4 h-4 text-accent" />
            ) : (
              <Sun className="w-4 h-4 text-warning" />
            )}
          </button>

          {/* Mobile Hamburger Menu Toggle Button (hidden on desktop) */}
          <button
            type="button"
            aria-label="Toggle navigation menu"
            aria-expanded={isMobileMenuOpen}
            onClick={() => setIsMobileMenuOpen((open) => !open)}
            className={`md:hidden p-2 rounded-lg border transition min-h-[44px] min-w-[44px] flex items-center justify-center ${
              isMobileMenuOpen
                ? 'bg-accent/20 border-accent text-accent shadow-xs'
                : 'bg-surface-elevated border-border text-text hover:text-accent hover:border-accent'
            }`}
          >
            {isMobileMenuOpen ? (
              <X className="w-5 h-5" />
            ) : (
              <Menu className="w-5 h-5" />
            )}
          </button>
        </div>
      </div>

      {/* Mobile Drawer Menu (Visible when hamburger is open) */}
      {isMobileMenuOpen && (
        <div
          role="dialog"
          aria-label="Mobile Navigation Menu"
          className="md:hidden border-t border-border bg-surface/98 backdrop-blur-xl px-4 py-4 space-y-4 shadow-2xl animate-in slide-in-from-top-2 duration-200"
        >
          {/* Mobile Primary Navigation Links */}
          <div className="space-y-1.5">
            <p className="text-[11px] font-bold uppercase tracking-wider text-text-muted px-1">
              Navigation
            </p>
            <div className="grid grid-cols-1 gap-2">
              {navItems.map((item) => {
                const Icon = item.icon;
                const isActive = activeTab === item.id;
                const isLive = item.id === 'DASHBOARD' && isRunning;

                return (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => handleMobileNavSelect(item.id)}
                    aria-current={isActive ? 'page' : undefined}
                    className={`w-full text-left px-3.5 py-3 rounded-xl flex items-center justify-between min-h-[48px] transition-all ${
                      isActive
                        ? 'bg-accent text-on-accent font-bold shadow-sm border border-accent'
                        : 'bg-surface-elevated text-text hover:bg-surface border border-border'
                    }`}
                  >
                    <div className="flex items-center gap-3">
                      <div
                        className={`p-2 rounded-lg ${
                          isActive ? 'bg-on-accent/15 text-on-accent' : 'bg-surface text-accent'
                        }`}
                      >
                        <Icon className="w-5 h-5 shrink-0" />
                      </div>
                      <div>
                        <div className="text-sm font-semibold">{item.label}</div>
                        <div
                          className={`text-[11px] ${
                            isActive ? 'text-on-accent/80' : 'text-text-muted'
                          }`}
                        >
                          {item.desc}
                        </div>
                      </div>
                    </div>
                    {isLive && (
                      <span className="flex items-center gap-1.5 text-xs font-bold px-2 py-0.5 rounded-full bg-danger/20 text-danger border border-danger/40">
                        <span className="w-2 h-2 rounded-full bg-danger animate-ping" />
                        REC
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          </div>

          {/* Mobile Quick Actions & Utilities */}
          <div className="pt-2 border-t border-border/80 space-y-2">
            <p className="text-[11px] font-bold uppercase tracking-wider text-text-muted px-1">
              Tools & Guide
            </p>
            <div className="grid grid-cols-2 gap-2">
              <button
                type="button"
                onClick={() => {
                  onOpenWidgets();
                  setIsMobileMenuOpen(false);
                }}
                className="w-full min-h-[44px] px-3 py-2.5 rounded-xl bg-surface-elevated border border-border text-text hover:border-accent hover:text-accent flex items-center gap-2 text-xs font-semibold transition"
              >
                <LayoutGrid className="w-4 h-4 text-accent shrink-0" />
                <span className="truncate">{t.widgetsPreview}</span>
              </button>

              <button
                type="button"
                onClick={() => {
                  onReplayTutorial();
                  setIsMobileMenuOpen(false);
                }}
                className="w-full min-h-[44px] px-3 py-2.5 rounded-xl bg-surface-elevated border border-border text-text hover:border-accent hover:text-accent flex items-center gap-2 text-xs font-semibold transition"
              >
                <HelpCircle className="w-4 h-4 text-accent shrink-0" />
                <span className="truncate">Guide</span>
              </button>
            </div>
          </div>
        </div>
      )}
    </header>
  );
};
