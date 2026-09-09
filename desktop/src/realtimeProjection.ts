// Pure presentation helpers shared by the store and behavioral tests.
export interface ProjectedRow { id:number; canonicalId?:string; requestId?:string; runId?:string; text:string }
export function mergeCanonical<T extends ProjectedRow>(rows:T[], row:T):T[] {
  const matches=(item:T)=>item.canonicalId===row.canonicalId || !!(row.runId && item.runId===row.runId) || !!(row.requestId && item.requestId===row.requestId);
  const index=rows.findIndex(matches);
  if(index<0) return [...rows,row];
  const canonical={...row,id:rows[index].id};
  return rows.flatMap((item,i)=>i===index?[canonical]:matches(item)?[]:[item]);
}
export function appendVisualDelta<T extends ProjectedRow>(rows:T[],runId:string,text:string):T[] {
  return rows.map(row=>row.runId===runId && !row.canonicalId && row.text.length+text.length<=128*1024?{...row,text:row.text+text}:row);
}
