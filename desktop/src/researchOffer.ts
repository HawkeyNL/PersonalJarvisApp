// Core may advise a web search after a reply. Nothing searches until the owner
// clicks: the click sends exactly this question as a new Research run.
export interface ResearchOffer { conversationId:string; question:string }

export function offerFrom(payload:{run:{conversation_id:string};research_offer?:{question:string}}):ResearchOffer|null {
  const question=payload.research_offer?.question?.trim();
  return question ? {conversationId:payload.run.conversation_id,question} : null;
}
