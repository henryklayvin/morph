import type { CSSProperties } from 'react';
import type { Capabilities, OptionSpec } from '../backend/types';
import { formatSize, type Item, operationFor } from '../state/items';

interface Props {
  item: Item;
  capabilities: Capabilities;
  onTarget(target: string): void;
  onOption(option: string, value: string): void;
  onConvert(): void;
  onCancel(): void;
  onDownload(): void;
  onRemove(): void;
}

export function ItemRow({ item, capabilities, ...on }: Props) {
  const operation = operationFor(capabilities, item);
  const name = (id: string) => capabilities.formats.find((f) => f.id === id)?.name ?? id;
  const source = item.sources[0];
  const busy = item.status === 'converting';
  const targets = item.sources.length > 1 ? [item.target ?? ''] : (source?.targets ?? []);
  const outputs = item.result?.outputs ?? [];

  return (
    <li className={`row row--${item.status}`}>
      {/* Square with the source format, round with the target once converted: the product's name, drawn. */}
      <div className="tile" style={{ '--progress': item.progress } as CSSProperties} aria-hidden="true">
        {busy ? `${Math.round(item.progress * 100)}%` : name((item.status === 'done' && item.target) || source?.format || '...')}
      </div>

      <div className="row__body">
        <p className="row__name">{item.label}</p>
        <p className="row__meta" role="status">
          {describe(item)}
        </p>
        {item.sources.map(
          (s) =>
            s.warning && (
              <p key={s.id} className="row__note">
                {s.warning}
              </p>
            ),
        )}
        {item.result?.warnings.map((warning) => (
          <p key={warning} className="row__note">
            {warning}
          </p>
        ))}
        {operation && !busy && (operation.options.length > 0 || operation.losses.length > 0) && (
          <details className="row__details">
            <summary>Opções e perdas</summary>
            <div className="options">
              {operation.options.map((spec) => (
                <Option
                  key={spec.key}
                  id={`${item.key}-${spec.key}`}
                  spec={spec}
                  value={item.options[spec.key] ?? String(spec.default)}
                  onChange={(value) => on.onOption(spec.key, value)}
                />
              ))}
            </div>
            <ul className="losses">
              {operation.losses.map((loss) => (
                <li key={loss.kind}>{loss.description}</li>
              ))}
              {operation.limitations.map((limitation) => (
                <li key={limitation}>{limitation}</li>
              ))}
            </ul>
          </details>
        )}
      </div>

      <div className="row__actions">
        {targets.length > 0 && (
          <label className="target">
            <span>para</span>
            <select
              aria-label={`Formato de destino de ${item.label}`}
              value={item.target ?? ''}
              disabled={busy || item.sources.length > 1}
              onChange={(event) => on.onTarget(event.target.value)}
            >
              {targets.map((target) => (
                <option key={target} value={target}>
                  {name(target)}
                </option>
              ))}
            </select>
          </label>
        )}
        {busy ? (
          <button type="button" className="quiet" onClick={on.onCancel}>
            Cancelar
          </button>
        ) : item.status === 'done' ? (
          <button type="button" className="primary" onClick={on.onDownload}>
            {outputs.length > 1 ? `Baixar ${outputs.length} arquivos` : 'Baixar'}
          </button>
        ) : (
          item.target && (
            <button type="button" className="quiet" onClick={on.onConvert}>
              Converter
            </button>
          )
        )}
        <button type="button" className="icon" aria-label={`Remover ${item.label}`} onClick={on.onRemove}>
          <svg viewBox="0 0 16 16" aria-hidden="true">
            <path d="M4 4l8 8M12 4l-8 8" />
          </svg>
        </button>
      </div>
    </li>
  );
}

function Option(props: { id: string; spec: OptionSpec; value: string; onChange(value: string): void }) {
  const { id, spec, value, onChange } = props;
  return (
    <div className="option">
      <label htmlFor={id}>{spec.description}</label>
      {spec.type === 'integer' && (
        <span className="option__range">
          <input
            id={id}
            type="range"
            min={spec.min}
            max={spec.max}
            value={value}
            onChange={(event) => onChange(event.target.value)}
          />
          <output htmlFor={id}>{value}</output>
        </span>
      )}
      {spec.type === 'boolean' && (
        <input
          id={id}
          type="checkbox"
          checked={value === 'true'}
          onChange={(event) => onChange(String(event.target.checked))}
        />
      )}
      {spec.type === 'choice' && (
        <select id={id} value={value} onChange={(event) => onChange(event.target.value)}>
          {spec.choices.map((choice) => (
            <option key={choice}>{choice}</option>
          ))}
        </select>
      )}
      {spec.type === 'color' && (
        <input id={id} type="color" value={value} onChange={(event) => onChange(event.target.value)} />
      )}
    </div>
  );
}

function describe(item: Item): string {
  const size = formatSize(item.size);
  switch (item.status) {
    case 'adding':
      return `${size} · identificando`;
    case 'ready':
      return item.target ? size : `${size} · nenhuma conversão disponível`;
    case 'converting':
      return 'Convertendo e verificando';
    case 'failed':
      return item.error ?? 'Falhou';
    case 'done': {
      const outputs = item.result?.outputs ?? [];
      const total = outputs.reduce((sum, output) => sum + output.size, 0);
      const count = outputs.length > 1 ? `${outputs.length} arquivos, ` : '';
      return `${size} → ${count}${formatSize(total)} · verificado em ${item.result?.elapsedMillis ?? 0} ms`;
    }
  }
}
