/**
 * Map over `items` running at most `concurrency` calls at once, preserving input order in the result.
 * Used to bound-parallelize per-tile blob downloads (decrypt is CPU-bound, fetch is IO-bound) so a large
 * gallery doesn't serialize one round-trip after another, without firing hundreds of requests at once.
 */
export async function mapPool<T, R>(
  items: readonly T[],
  concurrency: number,
  fn: (item: T, index: number) => Promise<R>,
): Promise<R[]> {
  const results = new Array<R>(items.length)
  let next = 0
  async function worker(): Promise<void> {
    for (let i = next++; i < items.length; i = next++) {
      results[i] = await fn(items[i], i)
    }
  }
  await Promise.all(Array.from({ length: Math.max(1, Math.min(concurrency, items.length)) }, worker))
  return results
}
