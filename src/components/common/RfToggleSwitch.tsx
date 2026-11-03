import React from 'react';

export interface RfToggleSwitchProps {
  id?: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
  label?: string;
  description?: string;
  disabled?: boolean;
  size?: 'sm' | 'md';
  variant?: 'accent' | 'warning' | 'danger' | 'success';
  className?: string;
  'aria-label'?: string;
}

export function RfToggleSwitch({
  id,
  checked,
  onChange,
  label,
  description,
  disabled = false,
  size = 'md',
  variant = 'accent',
  className = '',
  'aria-label': ariaLabel,
}: RfToggleSwitchProps) {
  const switchId = id || (label ? `rf-toggle-${label.toLowerCase().replace(/[^a-z0-9]+/g, '-')}` : undefined);

  // Variant track glow & fill when checked
  const activeTrackClass =
    variant === 'warning'
      ? 'bg-warning shadow-[0_0_10px_rgba(251,191,36,0.35)]'
      : variant === 'danger'
      ? 'bg-danger shadow-[0_0_10px_rgba(248,113,113,0.35)]'
      : variant === 'success'
      ? 'bg-success shadow-[0_0_10px_rgba(74,222,128,0.35)]'
      : 'bg-accent shadow-[0_0_10px_rgba(56,189,248,0.35)]';

  const activeThumbClass =
    variant === 'warning' || variant === 'danger' || variant === 'success'
      ? 'bg-slate-950'
      : 'bg-on-accent';

  const trackDimensions = size === 'sm' ? 'w-8 h-4.5' : 'w-10 h-5.5';
  const thumbDimensions = size === 'sm' ? 'w-3.5 h-3.5' : 'w-4 h-4';
  const thumbTranslate =
    size === 'sm'
      ? checked
        ? 'translate-x-3.5'
        : 'translate-x-0.5'
      : checked
      ? 'translate-x-4.5'
      : 'translate-x-0.5';

  return (
    <label
      htmlFor={switchId}
      className={`inline-flex items-center justify-between gap-3 cursor-pointer select-none ${
        disabled ? 'opacity-50 cursor-not-allowed' : ''
      } ${className}`}
    >
      {(label || description) && (
        <div className="flex flex-col">
          {label && <span className="text-xs font-medium text-text">{label}</span>}
          {description && <span className="text-[11px] text-text-muted">{description}</span>}
        </div>
      )}
      <div className="relative inline-flex items-center shrink-0">
        <input
          id={switchId}
          type="checkbox"
          role="switch"
          aria-checked={checked}
          aria-label={ariaLabel || label}
          checked={checked}
          disabled={disabled}
          onChange={(e) => onChange(e.target.checked)}
          className="sr-only peer"
        />
        <div
          className={`relative ${trackDimensions} rounded-full transition-colors duration-200 ease-in-out border border-transparent peer-focus-visible:ring-2 peer-focus-visible:ring-accent peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-bg ${
            checked
              ? activeTrackClass
              : 'bg-surface-elevated border-border-input/50 hover:border-border-input'
          }`}
        >
          <span
            className={`absolute top-0.5 left-0 ${thumbDimensions} rounded-full transition-transform duration-200 ease-in-out transform shadow-sm ${thumbTranslate} ${
              checked ? activeThumbClass : 'bg-text-muted'
            }`}
          />
        </div>
      </div>
    </label>
  );
}
