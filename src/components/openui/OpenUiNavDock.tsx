import React from 'react';
import type { LucideIcon } from 'lucide-react';

export interface NavDockItem<T extends string> {
  id: T;
  label: string;
  icon: LucideIcon;
  badge?: React.ReactNode;
}

export interface OpenUiNavDockProps<T extends string> {
  items: NavDockItem<T>[];
  activeId: T;
  onSelect: (id: T) => void;
  className?: string;
}

export function OpenUiNavDock<T extends string>({
  items,
  activeId,
  onSelect,
  className = '',
}: OpenUiNavDockProps<T>) {
  return (
    <nav
      aria-label="Mobile Bottom Navigation"
      className={`md:hidden fixed bottom-0 left-0 right-0 z-40 bg-surface/95 backdrop-blur-md border-t border-border shadow-lg px-2 py-1.5 flex items-center justify-around ${className}`}
    >
      {items.map((item) => {
        const Icon = item.icon;
        const isActive = activeId === item.id;

        return (
          <button
            key={item.id}
            type="button"
            onClick={() => onSelect(item.id)}
            aria-label={item.label}
            className={`flex flex-col items-center justify-center py-1 px-3 rounded-xl transition-all ${
              isActive
                ? 'text-accent font-bold scale-105'
                : 'text-text-muted hover:text-text'
            }`}
          >
            <div className="relative">
              <div
                className={`p-1.5 rounded-xl transition-all ${
                  isActive
                    ? 'bg-accent/20 border border-accent/40 shadow-[0_0_10px_rgba(56,189,248,0.3)]'
                    : 'bg-transparent'
                }`}
              >
                <Icon className="w-5 h-5" />
              </div>
              {item.badge && (
                <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-accent opacity-75" />
                  <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-accent" />
                </span>
              )}
            </div>
            <span className="text-[10px] mt-0.5 tracking-tight font-medium">
              {item.label}
            </span>
          </button>
        );
      })}
    </nav>
  );
}
