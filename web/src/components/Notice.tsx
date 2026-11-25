import type { ReactNode } from 'react';

/** Full-screen message for the moments when there is nothing to operate. */
export function Notice({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <main className="notice" role="status">
      <h1>{title}</h1>
      {children && <p>{children}</p>}
    </main>
  );
}
