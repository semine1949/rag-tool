/**
 * 背景装饰：3 个模糊光斑（blur 90px）+ 缓慢浮动动画
 * 固定定位且不接收事件，作为所有页面的统一底层
 */
export function Background() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      {/* 光斑 1：青色，左上 */}
      <div
        className="absolute -left-[10%] -top-[15%] h-[520px] w-[520px] animate-float rounded-full"
        style={{
          background: 'radial-gradient(circle, var(--orb-1), transparent 68%)',
          filter: 'blur(90px)',
        }}
      />
      {/* 光斑 2：紫色，右上 */}
      <div
        className="absolute -right-[8%] top-[6%] h-[460px] w-[460px] animate-float rounded-full"
        style={{
          background: 'radial-gradient(circle, var(--orb-2), transparent 68%)',
          filter: 'blur(90px)',
          animationDelay: '-6s',
          animationDirection: 'reverse',
        }}
      />
      {/* 光斑 3：蓝色，底部居中 */}
      <div
        className="absolute bottom-[-18%] left-[32%] h-[500px] w-[500px] animate-float rounded-full"
        style={{
          background: 'radial-gradient(circle, var(--orb-3), transparent 68%)',
          filter: 'blur(90px)',
          animationDelay: '-12s',
        }}
      />
    </div>
  );
}
