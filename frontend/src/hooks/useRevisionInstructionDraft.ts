import { useCallback, useEffect, useRef, useState } from 'react';

interface Draft {
  text: string;
  unsaved: boolean;
}

/** Only author text is cached; candidate identity and application authority stay server-side. */
export function useRevisionInstructionDraft(targetKey: string, open: boolean) {
  const memory = useRef(new Map<string, Draft>());
  const [current, setCurrent] = useState<Draft & { key: string }>({ key: '', text: '', unsaved: false });
  const storageKey = targetKey ? `writing-agent:revision-instructions:v1:${targetKey}` : '';

  useEffect(() => {
    if (!open || !storageKey) return;
    const cached = memory.current.get(storageKey);
    // A failed write must not be overwritten by an older stored value on reopening.
    let draft: Draft = cached ?? { text: '', unsaved: false };
    if (!cached?.unsaved) {
      try {
        const raw = localStorage.getItem(storageKey);
        const stored = raw === null ? null : JSON.parse(raw);
        if (stored !== null && (stored.version !== 1 || typeof stored.text !== 'string')) {
          throw new Error('Unsupported instruction draft');
        }
        draft = { text: stored?.text ?? '', unsaved: false };
      } catch {
        draft = { text: cached?.text ?? '', unsaved: true };
      }
    }
    memory.current.set(storageKey, draft);
    setCurrent({ key: storageKey, ...draft });
  }, [open, storageKey]);

  const setInstructions = useCallback((text: string) => {
    if (!storageKey) return;
    let unsaved = false;
    // Persist in the input event itself: an immediate reload must not outrun an effect.
    try {
      if (text === '') localStorage.removeItem(storageKey);
      else localStorage.setItem(storageKey, JSON.stringify({ version: 1, text }));
    } catch {
      unsaved = true;
    }
    const draft = { text, unsaved };
    memory.current.set(storageKey, draft);
    setCurrent({ key: storageKey, ...draft });
  }, [storageKey]);

  return {
    instructions: current.key === storageKey ? current.text : '',
    draftUnsaved: current.key === storageKey && current.unsaved,
    setInstructions,
    clearInstructions: useCallback(() => setInstructions(''), [setInstructions]),
  };
}
