export function speechRate(value:unknown):number {
  if(typeof value!=="number" || !Number.isFinite(value))return 1;
  return Math.min(2,Math.max(0.5,value));
}
