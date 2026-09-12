// Owns a single asynchronously registered presentation listener.
export class EventSubscription<T> {
  private generation = 0;
  private dispose: (()=>void)|undefined;

  clear():void {
    this.generation++;
    this.dispose?.();
    this.dispose=undefined;
  }

  async replace(
    register:(receive:(event:T)=>void)=>Promise<()=>void>,
    receive:(event:T)=>void,
    start:()=>Promise<void>,
  ):Promise<void> {
    this.clear();
    const generation=this.generation;
    const dispose=await register(event=>{
      if(generation===this.generation)receive(event);
    });
    if(generation!==this.generation){dispose();return;}
    this.dispose=dispose;
    try { await start(); }
    catch(error) {
      // A failed old start must never dispose the replacement listener.
      if(generation===this.generation)this.clear();
      throw error;
    }
  }
}
