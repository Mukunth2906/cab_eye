package com.cabeye.backend.memory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * How the rider has responded to the memory agent's suggestions — the input to the
 * confidence recalibration loop.
 *
 * <p>Two kinds are kept apart because they fail differently: a PROACTIVE suggestion ("PSG
 * College, like usual?") costs one turn when wrong; a REPAIR suggestion ("did you mean PSG
 * College?") replaces the rider's own words, so a rider who keeps rejecting those needs the
 * agent to step back sooner.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryStats {

    public String riderId;
    public int proactiveAccepted;
    public int proactiveRejected;
    public int repairAccepted;
    public int repairRejected;
    public long updatedAt;

    public MemoryStats() {}
}
