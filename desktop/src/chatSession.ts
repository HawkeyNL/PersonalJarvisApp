// Presentation lifetime only. This is not authentication or authorization.
export class ChatSession {
  private epoch = 0;
  private readonly resets = new Set<()=>void>();
  capture():number { return this.epoch; }
  current(epoch:number):boolean { return epoch===this.epoch; }
  onReset(reset:()=>void):()=>void { this.resets.add(reset); return ()=>this.resets.delete(reset); }
  invalidate():void { this.epoch++; for(const reset of this.resets) reset(); }
}
export const chatSession = new ChatSession();
