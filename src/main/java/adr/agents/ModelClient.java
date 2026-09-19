package adr.agents;

import adr.broker.RunContext;
import adr.domain.AgentId;

/** The model extension point. D0: RecordedModelClient. Later: LiveModelClient. */
public interface ModelClient {
  /** The next turn for this trigger, or null when there is none. */
  Turn next(AgentId agent, String phase, String trigger, RunContext ctx);
}
