import { cn } from '@/lib/utils/cn';

/** 骨架屏占位块 */
export function Skeleton({ className }: { className?: string }) {
  return <div className={cn('animate-pulse rounded-lg bg-white/[0.07]', className)} />;
}

/** 卡片形态的骨架屏 */
export function SkeletonCard({ lines = 3 }: { lines?: number }) {
  return (
    <div className="glass p-5">
      <Skeleton className="mb-4 h-4 w-1/3" />
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton key={i} className={cn('mb-2.5 h-3', i === lines - 1 ? 'w-2/3' : 'w-full')} />
      ))}
    </div>
  );
}
