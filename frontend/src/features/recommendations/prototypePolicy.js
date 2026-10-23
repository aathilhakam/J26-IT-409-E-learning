export const PROTOTYPE_SUCCESS_THRESHOLD = 0.85;

export function meetsPrototypeSuccessThreshold(probability) {
  return Number.isFinite(probability) && probability >= PROTOTYPE_SUCCESS_THRESHOLD;
}
