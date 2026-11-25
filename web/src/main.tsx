import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { LocalEngineBackend } from './backend/localEngine';
import './app.css';

const root = document.getElementById('root');
if (!root) throw new Error('Missing root element');

// Phase 3 adds a browser backend for the public build; the rest of the interface stays the same.
const backend = new LocalEngineBackend();

createRoot(root).render(
  <StrictMode>
    <App backend={backend} />
  </StrictMode>,
);
