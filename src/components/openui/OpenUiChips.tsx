import React from 'react';

export interface OpenUiChipItem<T extends string | number> {
  id: T;
  label: React.ReactNode;
  icon?: React.ReactNode;
  badge?: React.ReactNode;
}

export interface OpenUiChipsProps<T extends string | number> {
  items: OpenUiChipItem<T>[];
  selectedId: T;
  onChange: (id: T) => void;
  size?: 'sm' | 'md';
  className?: string;
  ariaLabel?: string;
}

export function OpenUiChips<T extends string | number>({
  items,
  selectedId,
  onChange,
  size = 'md',
  className = '',
  ariaLabel,
}: OpenUiChipsProps<T>) {
  const padClass = size === 'sm' ? 'px-2.5 py-1 text-xs' : 'px-3 py-1.5 text-xs sm:text-sm';

  return (
    <div
      role="radiogroup"
      aria-label={ariaLabel}
      className={`inline-flex items-center gap-1 bg-surface-elevated border border-border/80 rounded-xl p-1 select-none ${className}`}
    >
      {items.map((item) => {
        const isSelected = item.id === selectedId;
        return (
          <button
            key={String(item.id)}
            type="button"
            role="radio"
            aria-checked={isSelected}
            onClick={() => onChange(item.id)}
            className={`inline-flex items-center gap-1.5 rounded-lg font-medium transition-all ${padClass} ${
              isSelected
                ? 'bg-accent text-on-accent font-semibold shadow-sm'
                : 'text-text-muted hover:text-text hover:bg-surface'
            }`}
          >
            {item.icon && <span className="shrink-0">{item.icon}</span>}
            <span>{item.label}</span>
            {item.badge && <span className="shrink-0">{item.badge}</span>}
          </button>
        );
      })}
    </div>
  );
}
