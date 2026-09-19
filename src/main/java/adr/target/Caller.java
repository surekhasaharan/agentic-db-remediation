package adr.target;

/** The simulated database identity behind a call. The target checks it on every method. */
public enum Caller { agent_analysis, agent_remediator, agent_verifier, agent_supervisor, orders_app, dba_oncall }
