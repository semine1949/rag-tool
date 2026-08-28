import { IconCheck, IconClose } from '@/components/icons';
import {
  ALL_ROLES,
  PERMISSION_META,
  PERMISSION_ORDER,
  ROLE_LABEL,
  ROLE_PERMISSIONS,
} from '@/lib/rbac';
import type { RoleCode } from '@/lib/types';
import { cn } from '@/lib/utils/cn';

/** 角色列的高亮配色 */
const ROLE_COLOR: Record<RoleCode, string> = {
  SUPER_ADMIN: '#ef4444',
  TENANT_ADMIN: '#a855f7',
  KB_ADMIN: '#22d3ee',
  CONTRIBUTOR: '#3b82f6',
  VIEWER: '#8a98b5',
};

/** RBAC 四角色权限矩阵表 */
export function PermissionMatrix() {
  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[640px] border-collapse">
        <thead>
          <tr className="border-b border-line">
            <th className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wide text-muted">
              权限点
            </th>
            {ALL_ROLES.map((role) => (
              <th key={role} className="px-3 py-3 text-center">
                <div className="flex flex-col items-center gap-1">
                  <span
                    className="rounded-md px-2 py-0.5 text-[10px] font-bold"
                    style={{
                      background: `${ROLE_COLOR[role]}1f`,
                      color: ROLE_COLOR[role],
                    }}
                  >
                    {ROLE_LABEL[role]}
                  </span>
                  <span className="font-mono text-[9px] text-muted-2">{role}</span>
                </div>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {PERMISSION_ORDER.map((perm) => {
            const meta = PERMISSION_META[perm];
            return (
              <tr
                key={perm}
                className="border-b border-line/60 transition-colors last:border-0 hover:bg-white/[0.035]"
              >
                <td className="px-4 py-3">
                  <p className="text-xs font-medium text-text">{meta.label}</p>
                  <p className="mt-0.5 text-[10px] text-muted">{meta.desc}</p>
                  <code className="mt-1 inline-block font-mono text-[9px] text-muted-2">{perm}</code>
                </td>
                {ALL_ROLES.map((role) => {
                  const granted = ROLE_PERMISSIONS[role].includes(perm);
                  return (
                    <td key={role} className="px-3 py-3 text-center">
                      <span
                        className={cn(
                          'inline-flex h-6 w-6 items-center justify-center rounded-lg border',
                          granted
                            ? 'border-ok/30 bg-ok/12 text-ok'
                            : 'border-line bg-white/[0.03] text-muted-2',
                        )}
                        title={granted ? '允许' : '拒绝'}
                      >
                        {granted ? (
                          <IconCheck className="h-3.5 w-3.5" />
                        ) : (
                          <IconClose className="h-3 w-3" />
                        )}
                      </span>
                    </td>
                  );
                })}
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
