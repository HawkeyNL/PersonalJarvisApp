// Bounded correlation metadata only: no prompt bodies, tokens or retry calls.
export interface PendingRun { conversation:string|null; messageId:number; runId?:string }
export class PendingRuns {
  private readonly runs = new Map<string,PendingRun>();
  private readonly capacity:number;
  constructor(capacity=32) { this.capacity=capacity; }
  get full():boolean { return this.runs.size >= this.capacity; }
  get(id:string):PendingRun|undefined {
    const run=this.runs.get(id); return run ? {...run} : undefined;
  }
  set(id:string,run:PendingRun):boolean {
    if(this.runs.has(id) || this.full) return false;
    this.runs.set(id,{...run}); return true;
  }
  acknowledge(id:string,conversation:string,runId?:string):void {
    const run=this.runs.get(id);
    if(run) this.runs.set(id,{...run,conversation,runId:runId ?? run.runId});
  }
  delete(id:string):void { this.runs.delete(id); }
  clear():void { this.runs.clear(); }
  entries():Array<[string,PendingRun]> { return [...this.runs].map(([id,run])=>[id,{...run}]); }
  reconcile(id:string,runId:string,state:unknown):boolean {
    if(this.runs.get(id)?.runId!==runId) return false;
    if(state==="completed" || state==="failed" || state==="interrupted") { this.runs.delete(id); return true; }
    return false;
  }
}
