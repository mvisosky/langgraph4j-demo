package com.acme.support.graph.nodes;

import com.acme.support.ai.ResponseAssistants;
import com.acme.support.graph.SupportState;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeActionWithConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.acme.support.graph.SupportState.*;

/** Low-risk branch: answered by the fast model and routed straight to the output guardrail (no critic). */
@Component
public class SmallTalkNode implements NodeActionWithConfig<SupportState> {

    private final ResponseAssistants.SmallTalkResponder responder;

    public SmallTalkNode(ResponseAssistants.SmallTalkResponder responder) {
        this.responder = responder;
    }

    @Override
    public Map<String, Object> apply(SupportState state, RunnableConfig config) {
        String reply = responder.reply(state.str(CUSTOMER_PROFILE).orElse(""), state.recentTranscript(4));
        return update("smalltalk_agent: replied", DRAFT, reply);
    }
}
