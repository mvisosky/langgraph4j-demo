package com.acme.support.graph.nodes;

import com.acme.support.ai.ReflectionAssistants;
import com.acme.support.graph.SupportState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/** Rewrites the draft to address the critic's issues; loops back to the critic. */
@Component
public class ReviserNode implements NodeActionWithConfig<SupportState> {

    private final ReflectionAssistants.Reviser reviser;

    public ReviserNode(ReflectionAssistants.Reviser reviser) {
        this.reviser = reviser;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        int revision = state.integer(REVISIONS) + 1;
        String revised = reviser.revise(state.lastUserText(), state.str(EVIDENCE).orElse("(none)"),
                state.str(DRAFT).orElse(""), state.str(CRITIQUE).orElse(""));
        return update("reviser: revision #" + revision, DRAFT, revised, REVISIONS, revision);
    }
}
