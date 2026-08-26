import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { ToastProvider } from '@/components/ui';
import { AuthProvider } from '@/features/auth/AuthContext';
import { router } from '@/router';
import '@/styles/index.css';

// 应用入口：Toast -> Auth -> Router 依次包裹
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ToastProvider>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </ToastProvider>
  </StrictMode>,
);
