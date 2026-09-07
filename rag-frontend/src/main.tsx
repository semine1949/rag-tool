import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { ToastProvider } from '@/components/ui';
import { AuthProvider } from '@/features/auth/AuthContext';
import { ThemeProvider } from '@/lib/theme/ThemeContext';
import { router } from '@/router';
import '@/styles/index.css';

// 应用入口：Theme -> Toast -> Auth -> Router 依次包裹（主题置于最外层以全局生效）
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider>
      <ToastProvider>
        <AuthProvider>
          <RouterProvider router={router} />
        </AuthProvider>
      </ToastProvider>
    </ThemeProvider>
  </StrictMode>,
);
