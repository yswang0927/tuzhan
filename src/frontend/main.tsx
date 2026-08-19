import React from 'react'
import ReactDOM from 'react-dom/client'
import { HotkeysProvider } from "@blueprintjs/core";
import App from './App';
import { L10nProvider } from "@/l10n";
import { initTheme } from '@/utils/theme';

import "@blueprintjs/core/lib/css/blueprint.css";
import "@blueprintjs/datetime/lib/css/blueprint-datetime.css";

import './index.css'

// 初始化主题设置
initTheme();

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <L10nProvider>
      <HotkeysProvider>
      <App />
      </HotkeysProvider>
    </L10nProvider>
  </React.StrictMode>
)