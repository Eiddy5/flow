package org.cses.flow.core.domains.flows;


import org.cses.flow.core.domains.DeletableBase;
import org.cses.flow.core.utils.AssertUtil;
import org.paas.session.Session;
import org.paas.session.User;

import java.util.List;
import java.util.Map;

public abstract class AbstractFlow extends DeletableBase<FlowId> {
    private String key;
    private Long version;
    private String description;
    private Map<String, Object> variables;
    private List<? extends Input<?>> inputs;
    private List<Output> outputs;


    public AbstractFlow(FlowId id,
                        Session<? extends User> session,
                        String description,
                        Map<String, Object> variables,
                        List<? extends Input<?>> inputs,
                        List<Output> outputs) {
        super(id, session);
        this.key = id.key();
        this.version = id.version();
        this.description = description;
        this.variables = variables;
        this.inputs = inputs;
        this.outputs = outputs;
    }


    public String key(){
        return key;
    }

    public String description(){
        return description;
    }

    public Map<String, Object> variables(){
        return variables;
    }
    public List<? extends Input<?>> inputs(){
        return inputs;
    }
    public List<Output> outputs(){
        return outputs;
    }

    public Long version(){
        return version;
    }



}
