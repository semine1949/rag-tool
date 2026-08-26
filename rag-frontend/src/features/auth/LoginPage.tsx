import { useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Background } from '@/components/layout';
import { Badge, Button, Card, Input, Tabs, useToast } from '@/components/ui';
import {
  IconLayers,
  IconLock,
  IconMail,
  IconShield,
  IconSparkles,
  IconUser,
  IconWarning,
} from '@/components/icons';
import { ApiError } from '@/lib/api';
import { useAuth } from './AuthContext';

/** 平台特性介绍，展示在左侧品牌区 */
const FEATURES = [
  {
    icon: IconLayers,
    title: '三级数据隔离',
    desc: '租户 → 知识库 → 向量集合逐层隔离，跨租户数据零泄漏',
  },
  {
    icon: IconSparkles,
    title: '三种检索模式',
    desc: '纯向量 / 纯关键词 / 混合加权融合，按场景灵活切换',
  },
  {
    icon: IconShield,
    title: '四级 RBAC 权限',
    desc: '租户管理员到只读访客，细粒度控制到知识库维度',
  },
];

/** 登录失败次数上限，达到后提示锁定 */
const MAX_ATTEMPTS = 5;

/**
 * 登录 / 注册页
 * 左侧品牌介绍 + 右侧双卡片（Tabs 切换），含失败计数与锁定提示
 */
export function LoginPage() {
  const { user, login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();

  const [tab, setTab] = useState<'login' | 'register'>('login');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  /** 连续失败次数，用于锁定提示 */
  const [attempts, setAttempts] = useState(0);

  // 登录表单
  const [loginForm, setLoginForm] = useState({ username: 'tenant_admin', password: 'admin123' });
  // 注册表单
  const [regForm, setRegForm] = useState({
    username: '',
    email: '',
    password: '',
    confirm: '',
    tenantCode: 'acme',
  });

  // 已登录直接跳转
  if (user) {
    const from = (location.state as { from?: string } | null)?.from;
    return <Navigate to={from || '/dashboard'} replace />;
  }

  const locked = attempts >= MAX_ATTEMPTS;

  const handleLogin = async (e: FormEvent) => {
    e.preventDefault();
    if (locked) return;
    setError('');
    setLoading(true);
    try {
      await login(loginForm);
      toast.success('登录成功，欢迎回来');
      navigate('/dashboard', { replace: true });
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : '登录失败，请稍后重试';
      setError(msg);
      setAttempts((n) => n + 1);
    } finally {
      setLoading(false);
    }
  };

  const handleRegister = async (e: FormEvent) => {
    e.preventDefault();
    setError('');

    // 前端基础校验
    if (regForm.password.length < 6) {
      setError('密码长度至少 6 位');
      return;
    }
    if (regForm.password !== regForm.confirm) {
      setError('两次输入的密码不一致');
      return;
    }

    setLoading(true);
    try {
      await register({
        username: regForm.username,
        password: regForm.password,
        email: regForm.email,
        tenantCode: regForm.tenantCode,
      });
      toast.success('注册成功，已自动登录');
      navigate('/dashboard', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '注册失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="relative min-h-screen">
      <Background />

      <div className="mx-auto grid min-h-screen max-w-[1180px] items-center gap-10 px-6 py-10 lg:grid-cols-2 lg:gap-16">
        {/* 左侧：品牌与特性 */}
        <div className="hidden lg:block">
          <div className="mb-8 flex items-center gap-3">
            <span className="flex h-12 w-12 items-center justify-center rounded-2xl bg-grad text-[#04121a] shadow-glow">
              <IconSparkles className="h-6 w-6" />
            </span>
            <div>
              <h1 className="text-2xl font-bold tracking-tight text-text">NebulaKB</h1>
              <p className="text-xs text-muted">企业级 RAG 知识库平台</p>
            </div>
          </div>

          <h2 className="mb-4 text-[34px] font-bold leading-[1.25] tracking-tight">
            让企业知识
            <span className="text-grad"> 可检索、可溯源</span>
          </h2>
          <p className="mb-10 max-w-md text-sm leading-relaxed text-muted">
            统一接入多源文档，构建向量与关键词双通道检索，配合大模型生成带引用溯源的精准回答，
            全流程支持多租户隔离与细粒度权限管控。
          </p>

          <div className="space-y-4">
            {FEATURES.map((f) => {
              const Icon = f.icon;
              return (
                <div key={f.title} className="flex items-start gap-3.5">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl border border-line bg-grad-soft text-accent">
                    <Icon className="h-5 w-5" />
                  </span>
                  <div>
                    <p className="text-sm font-semibold text-text">{f.title}</p>
                    <p className="mt-0.5 text-xs leading-relaxed text-muted">{f.desc}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* 右侧：登录 / 注册卡片 */}
        <div className="mx-auto w-full max-w-[440px]">
          {/* 移动端品牌 */}
          <div className="mb-6 flex items-center justify-center gap-2.5 lg:hidden">
            <span className="flex h-10 w-10 items-center justify-center rounded-xl bg-grad text-[#04121a]">
              <IconSparkles className="h-5 w-5" />
            </span>
            <span className="text-lg font-bold text-text">NebulaKB</span>
          </div>

          <Card padding="lg" className="animate-fade">
            <div className="mb-6 flex justify-center">
              <Tabs
                items={[
                  { key: 'login', label: '登录' },
                  { key: 'register', label: '注册' },
                ]}
                value={tab}
                onChange={(k) => {
                  setTab(k as 'login' | 'register');
                  setError('');
                }}
              />
            </div>

            {/* 锁定提示 */}
            {locked && (
              <div className="mb-4 flex items-start gap-2.5 rounded-xl border border-danger/30 bg-danger/10 px-3.5 py-3">
                <IconWarning className="mt-0.5 h-4 w-4 shrink-0 text-danger" />
                <div>
                  <p className="text-xs font-semibold text-danger">账号已被临时锁定</p>
                  <p className="mt-0.5 text-[11px] leading-relaxed text-danger/80">
                    连续 {MAX_ATTEMPTS} 次密码错误，请 30 分钟后重试或联系租户管理员解锁。
                  </p>
                </div>
              </div>
            )}

            {/* 通用错误提示 */}
            {error && !locked && (
              <div className="mb-4 flex items-start gap-2.5 rounded-xl border border-danger/30 bg-danger/10 px-3.5 py-2.5">
                <IconWarning className="mt-0.5 h-4 w-4 shrink-0 text-danger" />
                <p className="text-xs leading-relaxed text-danger">{error}</p>
              </div>
            )}

            {tab === 'login' ? (
              <form onSubmit={handleLogin} className="space-y-4">
                <Input
                  label="用户名"
                  name="username"
                  autoComplete="username"
                  placeholder="请输入用户名"
                  prefixIcon={<IconUser className="h-4 w-4" />}
                  value={loginForm.username}
                  onChange={(e) => setLoginForm({ ...loginForm, username: e.target.value })}
                  disabled={locked}
                  required
                />
                <Input
                  label="密码"
                  name="password"
                  type="password"
                  autoComplete="current-password"
                  placeholder="请输入密码"
                  prefixIcon={<IconLock className="h-4 w-4" />}
                  value={loginForm.password}
                  onChange={(e) => setLoginForm({ ...loginForm, password: e.target.value })}
                  disabled={locked}
                  required
                  hint={
                    attempts > 0 && !locked
                      ? `已连续失败 ${attempts} 次，剩余 ${MAX_ATTEMPTS - attempts} 次机会`
                      : undefined
                  }
                />

                <div className="flex items-center justify-between pt-1">
                  <label className="flex cursor-pointer items-center gap-2 text-xs text-muted">
                    <input type="checkbox" defaultChecked className="accent-accent" />
                    记住登录状态
                  </label>
                  <button type="button" className="text-xs text-accent hover:underline">
                    忘记密码？
                  </button>
                </div>

                <Button type="submit" size="lg" loading={loading} disabled={locked} className="w-full">
                  登录平台
                </Button>

                <div className="rounded-xl border border-line bg-black/20 px-3.5 py-3">
                  <p className="mb-1.5 text-[11px] font-medium text-muted">演示账号（占位）</p>
                  <div className="flex flex-wrap gap-1.5">
                    <Badge tone="accent">tenant_admin / admin123</Badge>
                    <Badge tone="neutral">li.wei · KB_ADMIN</Badge>
                  </div>
                </div>
              </form>
            ) : (
              <form onSubmit={handleRegister} className="space-y-4">
                <Input
                  label="用户名"
                  name="reg-username"
                  placeholder="4-20 位字母、数字或下划线"
                  prefixIcon={<IconUser className="h-4 w-4" />}
                  value={regForm.username}
                  onChange={(e) => setRegForm({ ...regForm, username: e.target.value })}
                  required
                  minLength={4}
                />
                <Input
                  label="邮箱"
                  name="reg-email"
                  type="email"
                  placeholder="用于接收系统通知"
                  prefixIcon={<IconMail className="h-4 w-4" />}
                  value={regForm.email}
                  onChange={(e) => setRegForm({ ...regForm, email: e.target.value })}
                  required
                />
                <Input
                  label="租户编码"
                  name="reg-tenant"
                  placeholder="如 acme"
                  prefixIcon={<IconLayers className="h-4 w-4" />}
                  value={regForm.tenantCode}
                  onChange={(e) => setRegForm({ ...regForm, tenantCode: e.target.value })}
                  required
                  hint="新用户默认分配 VIEWER 只读角色"
                />
                <div className="grid gap-4 sm:grid-cols-2">
                  <Input
                    label="密码"
                    name="reg-password"
                    type="password"
                    placeholder="至少 6 位"
                    value={regForm.password}
                    onChange={(e) => setRegForm({ ...regForm, password: e.target.value })}
                    required
                  />
                  <Input
                    label="确认密码"
                    name="reg-confirm"
                    type="password"
                    placeholder="再次输入"
                    value={regForm.confirm}
                    onChange={(e) => setRegForm({ ...regForm, confirm: e.target.value })}
                    required
                  />
                </div>

                <Button type="submit" size="lg" loading={loading} className="w-full">
                  创建账号
                </Button>
                <p className="text-center text-[11px] leading-relaxed text-muted-2">
                  注册即表示同意平台服务条款与数据处理协议
                </p>
              </form>
            )}
          </Card>

          <p className="mt-5 text-center text-[11px] text-muted-2">
            JWT 认证 · accessToken 2 小时 / refreshToken 7 天自动续期
          </p>
        </div>
      </div>
    </div>
  );
}
