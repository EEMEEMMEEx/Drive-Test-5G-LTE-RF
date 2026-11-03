import React from 'react';

export interface OpenUiMetricProps {
  label: string;
  value: React.ReactNode;
  unit?: string;
  subValue?: React.ReactNode;
  icon?: React.ReactNode;
  variant?: 'default' | 'accent' | 'success' | 'warning' | 'danger';
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export function OpenUiMetric({
  label,
  value,
  unit,
  subValue,
  icon,
  variant = 'default',
  size = 'md',
  className = '',
}: OpenUiMetricProps) {
  const valueColorClass =
    variant === 'accent'
      ? 'text-accent'
      : variant === 'success'
      ? 'text-success'
      : variant === 'warning'
      ? 'text-warning'
      : variant === 'danger'
      ? 'text-danger'
      : 'text-text';

  const valueSizeClass =
    size === 'lg'
      ? 'text-2xl sm:text-3xl'
      : size === 'sm'
      ? 'text-base sm:text-lg'
      : 'text-lg sm:text-xl';

  return (
    <div
      className={`bg-surface-elevated border border-border/80 rounded-xl p-3 flex flex-col justify-between transition-all hover:border-accent/40 ${className}`}
    >
      <div className="flex items-center justify-between gap-1.5 mb-1">
        <span className="text-[11px] font-medium uppercase tracking-wider text-text-muted truncate">
          {label}
        </span>
        {icon && <span className="text-text-muted/70 shrink-0">{icon}</span>}
      </div>

      <div className="flex items-baseline gap-1">
        <span className={`font-mono font-bold ${valueSizeClass} ${valueColorClass} truncate`}>
          {value}
        </span>
        {unit && (
          <span className="text-xs font-medium text-text-muted font-sans">
            {unit}
          </span>
        )}
      </div>

      {subValue && (
        <div className="mt-1 text-[11px] text-text-muted truncate">
          {subValue}
        </div>
      )}
    </div>
  );
}
