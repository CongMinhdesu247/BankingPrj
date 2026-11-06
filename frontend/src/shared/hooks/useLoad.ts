import { useEffect, useState } from 'react';
export function useLoad<T>(loader: (signal: AbortSignal) => Promise<T>, dependencies: readonly unknown[] = []) {
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<{ data: T | null; loading: boolean; error: unknown }>({ data: null, loading: true, error: null });
  useEffect(() => {
    const controller = new AbortController();
    setState(previous => ({ data: previous.data, loading: previous.data === null, error: null }));
    Promise.resolve().then(() => loader(controller.signal)).then(data => { if (!controller.signal.aborted) setState({ data, loading: false, error: null }); })
      .catch(error => { if (!controller.signal.aborted) setState({ data: null, loading: false, error }); });
    return () => controller.abort();
    // Callers declare the values captured by their loader; revision is an explicit refresh.
  }, [...dependencies, revision]);
  return { ...state, reload: () => setRevision(value => value + 1) };
}
