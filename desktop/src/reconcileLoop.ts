// Coalesce reconnects without losing the final authoritative recovery request.
export class ReconcileLoop {
  private active:Promise<void>|undefined;
  private pending=false;
  private generation=0;
  get busy():boolean {return this.active!==undefined;}
  reset():void {this.generation++;this.pending=false;this.active=undefined;}
  request(work:()=>Promise<void>,complete:()=>void):Promise<void> {
    this.pending=true;
    if(this.active)return this.active;
    const generation=this.generation;
    this.active=Promise.resolve().then(async()=>{
      let failed=false;let failure:unknown;
      try {
        do {
          if(generation!==this.generation)return;
          this.pending=false;failed=false;
          try {await work();}catch(error){failed=true;failure=error;}
        } while(generation===this.generation && this.pending);
      } finally {
        if(generation===this.generation){this.active=undefined;complete();}
      }
      if(failed)throw failure;
    });
    return this.active;
  }
}
