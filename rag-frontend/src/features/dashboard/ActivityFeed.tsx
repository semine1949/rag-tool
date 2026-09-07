import {
  IconChat,
  IconDatabase,
  IconUpload,
  IconUsers,
  IconWarning,
} from '@/components/icons';
import type { ActivityItem } from '@/lib/types';
import { formatRelative } from '@/lib/utils/format';
import { cn } from '@/lib/utils/cn';

/** 活动类型 -> 图标与配色 */
const TYPE_META: Record<
  ActivityItem['type'],
  { icon: typeof IconUpload; cls: string }
> = {
  upload: { icon: IconUpload, cls: 'text-accent bg-accent/12 border-accent/25' },
  query: { icon: IconChat, cls: 'text-accent-2 bg-accent-2/12 border-accent-2/25' },
  kb: { icon: IconDatabase, cls: 'text-accent-3 bg-accent-3/12 border-accent-3/25' },
  user: { icon: IconUsers, cls: 'text-ok bg-ok/12 border-ok/25' },
  error: { icon: IconWarning, cls: 'text-danger bg-danger/12 border-danger/25' },
};

/** 活动流列表：时间轴样式 */
export function ActivityFeed({ items }: { items: ActivityItem[] }) {
  return (
    <ul className="relative space-y-1">
      {items.map((item, i) => {
        const meta = TYPE_META[item.type];
        const Icon = meta.icon;
        const last = i === items.length - 1;

        return (
          <li key={item.id} className="relative flex gap-3.5 pb-1">
            {/* 时间轴竖线 */}
            {!last && (
              <span className="absolute left-[15px] top-9 h-[calc(100%-20px)] w-px bg-line" />
            )}
            <span
              className={cn(
                'relative z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-xl border',
                meta.cls,
              )}
            >
              <Icon className="h-4 w-4" />
            </span>
            <div className="min-w-0 flex-1 rounded-xl px-2 py-1 transition-colors hover:bg-wash/[0.035]">
              <p className="text-xs leading-relaxed text-text">
                <span className="font-semibold">{item.actor}</span>
                <span className="mx-1 text-muted">{item.action}</span>
              </p>
              <p className="mt-0.5 truncate text-[11px] text-muted">{item.target}</p>
              <p className="mt-1 text-[10px] text-muted-2">{formatRelative(item.time)}</p>
            </div>
          </li>
        );
      })}
    </ul>
  );
}
