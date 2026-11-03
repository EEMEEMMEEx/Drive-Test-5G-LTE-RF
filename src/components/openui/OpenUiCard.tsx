import React from 'react';

export interface OpenUiCardProps {
  title?: React.ReactNode;
  subtitle?: React.ReactNode;
  icon?: React.ReactNode;
  badge?: React.ReactNode;
  actions?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
  bodyClassName?: string;
  dataTutorial?: string;
}

export function OpenUiCard({
  title,
  subtitle,
  icon,
  badge,
  actions,
  children,
  className = '',
  bodyClassName = '',
  dataTutorial,
}: OpenUiCardProps) {
  const hasHeader = title || subtitle || icon || badge || actions;

  return (
    <section
      data-tutorial={dataTutorial}
      className={`bg-surface border border-border rounded-2xl shadow-sm transition-all duration-200 overflow-hidden ${className}`}
    >
      {hasHeader && (
        <div className="px-4 py-3 sm:px-5 sm:py-3.5 border-b border-border/80 flex flex-wrap items-center justify-between gap-2.5">
          <div className="flex items-center gap-2.5 min-w-0">
            {icon && (
              <div className="w-8 h-8 rounded-xl bg-accent/15 border border-accent/30 flex items-center justify-center text-accent shrink-0">
                {icon}
              </div>
            )}
            <div className="min-w-0">
              {title && (
                <div className="text-sm font-bold tracking-tight text-text truncate">
                  {title}
                </div>
              )}
              {subtitle && (
                <div className="text-[11px] text-text-muted truncate">
                  {subtitle}
                </div>
              )}
            </div>
          </div>

          {(badge || actions) && (
            <div className="flex items-center gap-2 shrink-0">
              {badge}
              {actions}
            </div>
          )}
        </div>
      )}

      <div className={`p-4 sm:p-5 ${bodyClassName}`}>{children}</div>
    </section>
  );
}
