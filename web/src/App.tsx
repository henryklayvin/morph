import { type DragEvent, useRef, useState } from 'react';
import type { ConversionBackend } from './backend/types';
import { ItemRow } from './components/ItemRow';
import { Notice } from './components/Notice';
import { mergeable } from './state/items';
import { useMorph } from './state/useMorph';

export function App({ backend }: { backend: ConversionBackend }) {
  const morph = useMorph(backend);
  const picker = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  if (morph.phase === 'connecting') return <Notice title="Abrindo o Morph..." />;
  if (morph.phase === 'ended') {
    return (
      <Notice title="Morph encerrado">
        Os arquivos temporários foram apagados. Esta aba já pode ser fechada.
      </Notice>
    );
  }
  if (morph.phase === 'refused' || !morph.capabilities) {
    return (
      <Notice title="Não foi possível abrir esta sessão">
        {morph.reason} No terminal, execute <code>morph web</code> ou use o link mais recente exibido por ele.
      </Notice>
    );
  }

  const { capabilities, items } = morph;
  const pending = items.filter((item) => (item.status === 'ready' || item.status === 'failed') && item.target);
  const join = mergeable(capabilities, items);

  const onDrop = (event: DragEvent) => {
    event.preventDefault();
    setDragging(false);
    void morph.add(event.dataTransfer.files);
  };

  return (
    <div
      className={dragging ? 'page page--dragging' : 'page'}
      onDragOver={(event) => {
        event.preventDefault();
        setDragging(true);
      }}
      onDragLeave={(event) => {
        if (event.currentTarget === event.target) setDragging(false);
      }}
      onDrop={onDrop}
    >
      <header className="bar">
        <h1 className="brand">
          <svg viewBox="0 0 32 32" aria-hidden="true">
            <rect x="2" y="9" width="14" height="14" rx="2" />
            <circle cx="23" cy="16" r="7" />
          </svg>
          morph
        </h1>
        <p className="seal" title={`${morph.info?.engine ?? ''}`}>
          <span className="seal__dot" aria-hidden="true" />
          Tudo acontece neste computador
        </p>
        <button type="button" className="quiet" onClick={() => void morph.end()}>
          Encerrar
        </button>
      </header>

      <main>
        <section className={items.length ? 'drop drop--small' : 'drop'}>
          <input
            ref={picker}
            id="picker"
            className="visually-hidden"
            type="file"
            multiple
            onChange={(event) => {
              void morph.add(event.target.files ?? []);
              event.target.value = '';
            }}
          />
          <label htmlFor="picker" className="drop__label">
            <strong>{items.length ? 'Adicionar arquivos' : 'Solte arquivos aqui ou escolha no computador'}</strong>
            {items.length === 0 && (
              <span>
                {capabilities.formats
                  .filter((f) => capabilities.operations.some((o) => o.source === f.id && o.available))
                  .map((f) => f.name)
                  .join(', ')}
                . Nada é enviado para a internet.
              </span>
            )}
          </label>
        </section>

        {items.length > 0 && (
          <>
            <ul className="list">
              {items.map((item) => (
                <ItemRow
                  key={item.key}
                  item={item}
                  capabilities={capabilities}
                  onTarget={(target) => morph.dispatch({ type: 'target', key: item.key, target })}
                  onOption={(option, value) => morph.dispatch({ type: 'option', key: item.key, option, value })}
                  onConvert={() => void morph.convert(item)}
                  onCancel={() => morph.cancel(item)}
                  onDownload={() => void morph.download(item)}
                  onRemove={() => void morph.remove(item)}
                />
              ))}
            </ul>
            <div className="actions">
              {join && (
                <button type="button" className="quiet" onClick={morph.merge}>
                  Juntar {join.sources.length} imagens em um {join.target.toUpperCase()}
                </button>
              )}
              <button
                type="button"
                className="primary"
                disabled={pending.length === 0}
                onClick={() => pending.forEach((item) => void morph.convert(item))}
              >
                {pending.length > 1 ? `Converter ${pending.length} arquivos` : 'Converter'}
              </button>
            </div>
          </>
        )}
      </main>

      <footer className="foot">
        Morph {morph.info?.version} · {morph.info?.engine}
      </footer>
    </div>
  );
}
