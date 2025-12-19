package org.fmgroup.mediator.plugins.scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fmgroup.mediator.language.RawElement;
import org.fmgroup.mediator.language.Templated;
import org.fmgroup.mediator.language.ValidationException;
import org.fmgroup.mediator.language.entity.PortDeclaration;
import org.fmgroup.mediator.language.entity.automaton.Automaton;
import org.fmgroup.mediator.language.entity.automaton.Transition;
import org.fmgroup.mediator.language.entity.automaton.TransitionGroup;
import org.fmgroup.mediator.language.entity.automaton.TransitionSingle;
import org.fmgroup.mediator.language.entity.system.ComponentDeclaration;
import org.fmgroup.mediator.language.entity.system.Connection;
import org.fmgroup.mediator.language.entity.system.InternalDeclaration;
import org.fmgroup.mediator.language.entity.system.System;
import org.fmgroup.mediator.language.scope.VariableDeclaration;
import org.fmgroup.mediator.language.statement.AssignmentStatement;
import org.fmgroup.mediator.language.statement.Statement;
import org.fmgroup.mediator.language.statement.SynchronizingStatement;
import org.fmgroup.mediator.language.term.BinaryOperatorTerm;
import org.fmgroup.mediator.language.term.BoolValue;
import org.fmgroup.mediator.language.term.EnumBinaryOperator;
import org.fmgroup.mediator.language.term.EnumSingleOperator;
import org.fmgroup.mediator.language.term.IdValue;
import org.fmgroup.mediator.language.term.PortVariableType;
import org.fmgroup.mediator.language.term.PortVariableValue;
import org.fmgroup.mediator.language.term.SingleOperatorTerm;
import org.fmgroup.mediator.language.term.Term;
import org.fmgroup.mediator.language.term.Value;
import org.fmgroup.mediator.language.type.Type;
import org.fmgroup.mediator.language.type.termType.BoolType;
import org.fmgroup.mediator.language.property.Property;
import org.fmgroup.mediator.language.property.PropertyCollection;
import org.fmgroup.mediator.language.property.PathFormulae.PathFormulae;
import org.fmgroup.mediator.language.property.PathFormulae.AtomicPathFormulae;
import org.fmgroup.mediator.language.property.PathFormulae.AllPathFormulae;
import org.fmgroup.mediator.language.property.StateFormulae.StateFormulae;
import org.fmgroup.mediator.language.property.StateFormulae.GloballyStateFormulae;
import org.fmgroup.mediator.language.term.FieldTerm;
import org.fmgroup.mediator.language.term.IteTerm;

/**
 * Scheduler class responsible for flattening the system hierarchy into a single automaton.
 * It handles component instantiation, connection resolution, and transition synchronization.
 */
public class Scheduler {

    /**
     * Schedules a System into a single Automaton.
     * This involves flattening components, resolving connections, and synchronizing transitions.
     *
     * @param sys The system to schedule.
     * @return A flattened Automaton representing the entire system.
     * @throws ValidationException If validation fails during scheduling.
     */
    public static Automaton Schedule(System sys) throws ValidationException {
        Automaton a = new Automaton();
        a.setParent(sys.getParent());
        a.setName(sys.getName());
        a.setTemplate(sys.getTemplate().copy(a));
        a.setEntityInterface(sys.getEntityInterface().copy(a));

        List<Automaton> entities = new ArrayList<>();
        Map<Automaton, List<Transition>> extTransitions = new HashMap<>();
        Map<Automaton, Map<String, Type>> typeRewriteMaps = new HashMap<>();
        Map<Automaton, Map<String, Term>> termRewriteMaps = new HashMap<>();

        // Process internal declarations
        for (InternalDeclaration internalDeclaration : sys.getInternalCollection().getDeclarationList()) {
            for (String identifier : internalDeclaration.getIdentifiers()) {
                for (PortVariableType type : PortVariableType.values()) {
                    VariableDeclaration variableDeclaration = new VariableDeclaration();
                    a.getLocalVars().addDeclaration(variableDeclaration);
                    variableDeclaration.addIdentifier(Scheduler.getNodeVariableName(identifier, type));
                    if (type == PortVariableType.VALUE) {
                        variableDeclaration.setType(internalDeclaration.getType());
                    } else {
                        variableDeclaration.setType(new BoolType());
                    }
                }
            }
        }

        // Process components
        for (ComponentDeclaration componentDeclaration : sys.getComponentCollection().getDeclarationList()) {
            for (String componentName : componentDeclaration.getIdentifiers()) {
                Templated rawComp = componentDeclaration.getType().getProviderWithNoTemplate();
                if (rawComp instanceof System) {
                    rawComp = Scheduler.Schedule((System) rawComp);
                }
                Automaton automatonComp = Scheduler.Canonicalize((Automaton) rawComp);
                
                Map<String, Type> typeRewriteMap = new HashMap<>();
                Map<String, Term> termRewriteMap = new HashMap<>();

                // Rewrite local variables
                for (VariableDeclaration vardecl : automatonComp.getLocalVars().getDeclarationList()) {
                    VariableDeclaration newvardecl = vardecl.copy(null).addPrefix(componentName + "_");
                    a.getLocalVars().addDeclaration(newvardecl);
                    for (int i = 0; i < vardecl.size(); ++i) {
                        termRewriteMap.put(vardecl.getIdentifier(i), 
                            new IdValue().setParent(a).setIdentifier(newvardecl.getIdentifier(i)));
                    }
                }

                // Rewrite port variables
                for (PortDeclaration portdecl : automatonComp.getEntityInterface().getDeclarationList()) {
                    for (String identifier : portdecl.getIdentifiers()) {
                        for (PortVariableType type : PortVariableType.values()) {
                            VariableDeclaration vardecl = new VariableDeclaration();
                            a.getLocalVars().addDeclaration(vardecl);
                            String varname = Scheduler.getPortVariableName(componentName, identifier, type);
                            vardecl.addIdentifier(varname);
                            if (type == PortVariableType.VALUE) {
                                vardecl.setType(portdecl.getType());
                            } else {
                                vardecl.setType(new BoolType());
                            }
                            termRewriteMap.put(identifier + "." + type.toString(), 
                                new IdValue().setParent(a).setIdentifier(varname));
                        }
                    }
                }
                entities.add(automatonComp);
                typeRewriteMaps.put(automatonComp, typeRewriteMap);
                termRewriteMaps.put(automatonComp, termRewriteMap);
                
                // Collect properties from component
                Scheduler.collectProperties(a, automatonComp, termRewriteMap, componentName);
            }
        }

        // Process connections
        for (Connection connection : sys.getConnections()) {
            Automaton automaton;
            Templated templated = connection.getProviderWithNoTemplate();
            if (templated instanceof System) {
                automaton = Scheduler.Schedule((System) templated);
            } else {
                automaton = (Automaton) templated;
            }
            
            automaton = Scheduler.Canonicalize(automaton);
            Map<String, Type> typeRewriteMap = new HashMap<>();
            Map<String, Term> termRewriteMap = new HashMap<>();
            
            int connIndex = sys.getConnections().indexOf(connection);
            
            // Rewrite connection local variables
            for (VariableDeclaration variableDeclaration : automaton.getLocalVars().getDeclarationList()) {
                VariableDeclaration newvardecl = variableDeclaration.copy(null)
                    .addPrefix(String.format("_%s_%d_", automaton.getName(), connIndex));
                a.getLocalVars().addDeclaration(newvardecl);
                for (int i = 0; i < variableDeclaration.size(); ++i) {
                    termRewriteMap.put(variableDeclaration.getIdentifier(i), 
                        new IdValue().setParent(a).setIdentifier(newvardecl.getIdentifier(i)));
                }
            }

            // Rewrite connection port variables
            for (int i = 0; i < connection.getPortIdentifiers().size(); ++i) {
                String portId = automaton.getEntityInterface().getDeclarationIdentifier(i);
                for (PortVariableType type : PortVariableType.values()) {
                    Term extPortVar;
                    if (connection.getPortIdentifiers().get(i).getOwner() != null) {
                        extPortVar = new IdValue().setParent(a).setIdentifier(
                            Scheduler.getPortVariableName(
                                connection.getPortIdentifiers().get(i).getOwner(), 
                                connection.getPortIdentifiers().get(i).getPortName(), 
                                type
                            )
                        );
                    } else {
                        extPortVar = new PortVariableValue().setParent(a)
                            .setPortIdentifier(connection.getPortIdentifiers().get(i))
                            .setPortVariableType(type);
                    }
                    termRewriteMap.put(portId + "." + type.toString(), extPortVar);
                }
            }
            
            entities.add(automaton);
            typeRewriteMaps.put(automaton, typeRewriteMap);
            termRewriteMaps.put(automaton, termRewriteMap);
            
            // Collect properties from connection
            Scheduler.collectProperties(a, automaton, termRewriteMap, String.format("_%s_%d", automaton.getName(), connIndex));
        }

        TransitionGroup tg = new TransitionGroup().setParent(a);
        a.addTransition(tg);

        // Process transitions
        for (Automaton automaton : entities) {
            assert (automaton.getTransitions().size() == 1 && automaton.getTransition(0) instanceof TransitionGroup);
            extTransitions.put(automaton, new ArrayList<>());
            extTransitions.get(automaton).add(null); // Add null for synchronization placeholder
            
            for (Transition t : ((TransitionGroup) automaton.getTransition(0)).getTransitions()) {
                assert (t instanceof TransitionSingle);
                if (((TransitionSingle) t).isInternal()) {
                    tg.addTransition(t.refactor(typeRewriteMaps.get(automaton), termRewriteMaps.get(automaton), tg));
                } else {
                    extTransitions.get(automaton).add(t.refactor(typeRewriteMaps.get(automaton), termRewriteMaps.get(automaton), a));
                }
            }
        }

        // Generate combinations for synchronization
        List<Map<Automaton, Transition>> combinations = new ArrayList<>();
        combinations.add(new HashMap<>());
        
        for (Automaton automaton : entities) {
            List<Map<Automaton, Transition>> temp = new ArrayList<>();
            List<Transition> transList = extTransitions.get(automaton);
            
            for (Transition t : transList) {
                for (Map<Automaton, Transition> prevCombination : combinations) {
                    Map<Automaton, Transition> newCombination = new HashMap<>(prevCombination);
                    newCombination.put(automaton, t);
                    temp.add(newCombination);
                }
            }
            combinations = temp;
        }

        // Synchronize transitions
        for (Map<Automaton, Transition> combination : combinations) {
            TransitionSingle syncTrans = Scheduler.Synchronize(combination, a);
            if (syncTrans != null) {
                tg.addTransition(syncTrans);
            }
        }
        
        return a;
    }

    public static TransitionSingle Synchronize(Map<Automaton, Transition> combination, Automaton parent) throws ValidationException {
        Term guard = null;
        TopoGraph<Statement> graph = new TopoGraph<>();
        
        for (Transition transition : combination.values()) {
            if (transition == null) continue;
            assert (transition instanceof TransitionSingle);
            
            // Combine guards
            if (guard == null) {
                guard = transition.getGuard();
            } else {
                guard = new BinaryOperatorTerm()
                    .setOpr(EnumBinaryOperator.LAND)
                    .setLeft(guard)
                    .setRight(transition.getGuard());
            }

            // Build dependency graph
            TransitionSingle ts = (TransitionSingle) transition;
            for (int i = -1; i < ts.size(); ++i) {
                List<TopoGraphVertice> froms = new ArrayList<>();
                List<TopoGraphVertice> tos = new ArrayList<>();
                
                if (i == -1) {
                    froms.add(graph.createVirtualNode());
                } else if (ts.getStatement(i) instanceof SynchronizingStatement) {
                    for (SynchronizingStatement st : ((SynchronizingStatement) ts.getStatement(i)).split()) {
                        froms.add(graph.getOrCreateNode(st));
                    }
                } else {
                    froms.add(graph.getOrCreateNode(ts.getStatement(i)));
                }
                
                if (i + 1 == ts.size()) {
                    tos.add(graph.createVirtualNode());
                } else if (ts.getStatement(i + 1) instanceof SynchronizingStatement) {
                    for (SynchronizingStatement st : ((SynchronizingStatement) ts.getStatement(i + 1)).split()) {
                        tos.add(graph.getOrCreateNode(st));
                    }
                } else {
                    tos.add(graph.getOrCreateNode(ts.getStatement(i + 1)));
                }
                
                for (TopoGraphVertice from : froms) {
                    for (TopoGraphVertice to : tos) {
                        graph.connect(from, to);
                    }
                }
            }
        }

        // Validate graph
        boolean valid = true;
        if (graph.vertices.isEmpty()) {
            valid = false;
        }
        
        for (TopoGraphVertice v : graph.vertices) {
            if (!(v.element instanceof SynchronizingStatement)) continue;
            
            // Check if synchronization is valid (in-degree 2, out-degree 2)
            // Or if it's an external port (doesn't contain "_")
            if ((graph.countInEdges(v) == 2 && graph.countOutEdges(v) == 2) || 
                !((SynchronizingStatement) v.element).getSynchronizedPort(0).getPortName().contains("_")) {
                continue;
            }
            
            valid = false;
            break;
        }
        
        if (!valid) {
            return null;
        }

        // Topological sort to order statements
        List<Statement> list = graph.TopologySort();
        
        // Replace internal synchronizing statements with boolean flags
        for (int i = 0; i < list.size(); ++i) {
            Statement s = list.get(i);
            if (!(s instanceof SynchronizingStatement)) continue;
            
            assert (((SynchronizingStatement) s).getSynchronizedPorts().size() == 1);
            String portId = ((SynchronizingStatement) s).getSynchronizedPort(0).getPortName();
            
            if (!portId.contains("_")) continue;
            
            int index = list.indexOf(s);
            list.remove(index);
            
            // Add reqRead = false
            list.add(index, new AssignmentStatement()
                .setTarget(new IdValue().setParent(parent).setIdentifier(portId + "_reqRead"))
                .setExpr(new BoolValue().setValue(false)));
                
            // Add reqWrite = false
            list.add(index, new AssignmentStatement()
                .setTarget(new IdValue().setParent(parent).setIdentifier(portId + "_reqWrite"))
                .setExpr(new BoolValue().setValue(false)));
        }
        
        TransitionSingle trans = new TransitionSingle();
        trans.setGuard(guard);
        trans.setStatements(list);
        return trans;
    }

    public static List<Transition> CanonicalizeTransitions(Term cond, List<Transition> transitions, RawElement parent) throws ValidationException {
        List<Transition> ctrans = new ArrayList<>();
        if (cond == null) {
            cond = new BoolValue().setValue(false);
        }
        
        for (Transition t : transitions) {
            if (t instanceof TransitionSingle) {
                TransitionSingle tnew = (TransitionSingle) t.copy(parent);
                tnew.setGuard(new BinaryOperatorTerm()
                    .setParent(tnew)
                    .setOpr(EnumBinaryOperator.LAND)
                    .setLeft(new SingleOperatorTerm()
                        .setOpr(EnumSingleOperator.LNOT)
                        .setTerm(cond.copy(parent)))
                    .setRight(tnew.getGuard()));
                ctrans.add(tnew);
            } else {
                ctrans.addAll(Scheduler.CanonicalizeTransitions(cond, ((TransitionGroup) t).getTransitions(), parent));
            }
            
            if (t.getParent() instanceof TransitionGroup) continue;
            
            cond = new BinaryOperatorTerm()
                .setOpr(EnumBinaryOperator.LOR)
                .setLeft(cond)
                .setRight(t.getGuard());
        }
        return ctrans;
    }

    public static Automaton Canonicalize(Automaton a) throws ValidationException {
        Automaton an = new Automaton();
        an.setParent(a.getParent());
        an.setName(a.getName());
        
        if (a.getTemplate() != null) {
            an.setTemplate(a.getTemplate().copy(an));
        }
        an.setEntityInterface(a.getEntityInterface().copy(an));
        an.setLocalVars(a.getLocalVars().copy(an));
        
        if (a.getProperties() != null) {
            an.setProperties(a.getProperties().copy(an));
        }
        
        TransitionGroup tg = new TransitionGroup();
        tg.setParent(an);
        
        try {
            tg.setTransitions(Scheduler.CanonicalizeTransitions(null, a.getTransitions(), tg));
        } catch (ValidationException e) {
            e.printStackTrace();
        }
        
        an.addTransition(tg);
        return an;
    }

    private static String getNodeVariableName(String nodeId, PortVariableType type) {
        return String.format("%s_%s", nodeId, type.toString());
    }

    private static String getPortVariableName(String componentId, String portId, PortVariableType type) {
        return String.format("%s_%s_%s", componentId, portId, type.toString());
    }

    private static void collectProperties(Automaton target, Automaton source, Map<String, Term> termRewriteMap, String prefix) throws ValidationException {
        PropertyCollection pc = source.getProperties();
        if (pc == null) {
            // Debug output removed for cleaner production code, or use a logger
            return;
        }
        
        Map<String, Property> props = pc.getPropertiesMap();
        if (props == null) return;

        if (target.getProperties() == null) {
            target.setProperties(new PropertyCollection());
        }

        for (Map.Entry<String, Property> entry : props.entrySet()) {
             String propName = entry.getKey();
             Property p = entry.getValue();
             
             PathFormulae pf = p.getFormulae();
             
             // Handle AllPathFormulae (A G ...)
             if (pf instanceof AllPathFormulae) {
                 StateFormulae sf = ((AllPathFormulae) pf).getFormula();
                 if (sf instanceof GloballyStateFormulae) {
                     StateFormulae inner = ((GloballyStateFormulae) sf).getFormula();
                     if (inner instanceof AtomicPathFormulae) {
                         Term t = ((AtomicPathFormulae) inner).getTerm();
                         Term newT = Scheduler.rewriteTerm(t, termRewriteMap, target);
                         
                         Property newP = new Property();
                         AtomicPathFormulae newInner = new AtomicPathFormulae();
                         newInner.setTerm(newT);
                         
                         GloballyStateFormulae newG = new GloballyStateFormulae();
                         newG.setFormula(newInner);
                         
                         AllPathFormulae newA = new AllPathFormulae();
                         newA.setFormula(newG);
                         
                         newP.setFormulae(newA);
                         
                         String newPropName = prefix + "_" + propName;
                         target.getProperties().putProperty(newPropName, newP);
                     }
                 }
             }
             // Handle simple AtomicPathFormulae
             else if (pf instanceof AtomicPathFormulae) {
                 Term t = ((AtomicPathFormulae) pf).getTerm();
                 Term newT = Scheduler.rewriteTerm(t, termRewriteMap, target);
                 
                 Property newP = new Property();
                 AtomicPathFormulae newPf = new AtomicPathFormulae();
                 newPf.setTerm(newT);
                 newP.setFormulae(newPf);
                 
                 String newPropName = prefix + "_" + propName;
                 target.getProperties().putProperty(newPropName, newP);
             }
        }
    }

    private static void collectProperties(Automaton target, Automaton source, Map<String, Term> termRewriteMap, String prefix) throws ValidationException {
        PropertyCollection pc = source.getProperties();
        if (pc == null) {
            return;
        }
        
        Map<String, Property> props = pc.getPropertiesMap();
        if (props == null) return;

        if (target.getProperties() == null) {
            target.setProperties(new PropertyCollection());
        }

        for (Map.Entry<String, Property> entry : props.entrySet()) {
             String propName = entry.getKey();
             Property p = entry.getValue();
             
             PathFormulae pf = p.getFormulae();
             
             // Handle AllPathFormulae (A G ...)
             if (pf instanceof AllPathFormulae) {
                 StateFormulae sf = ((AllPathFormulae) pf).getFormula();
                 if (sf instanceof GloballyStateFormulae) {
                     StateFormulae inner = ((GloballyStateFormulae) sf).getFormula();
                     if (inner instanceof AtomicPathFormulae) {
                         Term t = ((AtomicPathFormulae) inner).getTerm();
                         Term newT = Scheduler.rewriteTerm(t, termRewriteMap, target);
                         
                         Property newP = new Property();
                         AtomicPathFormulae newInner = new AtomicPathFormulae();
                         newInner.setTerm(newT);
                         
                         GloballyStateFormulae newG = new GloballyStateFormulae();
                         newG.setFormula(newInner);
                         
                         AllPathFormulae newA = new AllPathFormulae();
                         newA.setFormula(newG);
                         
                         newP.setFormulae(newA);
                         
                         String newPropName = prefix + "_" + propName;
                         target.getProperties().putProperty(newPropName, newP);
                     }
                 }
             }
             // Handle simple AtomicPathFormulae
             else if (pf instanceof AtomicPathFormulae) {
                 Term t = ((AtomicPathFormulae) pf).getTerm();
                 Term newT = Scheduler.rewriteTerm(t, termRewriteMap, target);
                 
                 Property newP = new Property();
                 AtomicPathFormulae newPf = new AtomicPathFormulae();
                 newPf.setTerm(newT);
                 newP.setFormulae(newPf);
                 
                 String newPropName = prefix + "_" + propName;
                 target.getProperties().putProperty(newPropName, newP);
             }
        }
    }

    private static Term rewriteTerm(Term t, Map<String, Term> map, RawElement parent) throws ValidationException {
        if (t == null) return null;

        if (t instanceof PortVariableValue) {
            PortVariableValue pvv = (PortVariableValue) t;
            String portName = pvv.getPortIdentifier().getPortName();
            String type = pvv.getPortVariableType().toString();
            
            String key = portName + "." + type;
            if (map.containsKey(key)) {
                return map.get(key).copy(parent);
            }
            return t.copy(parent);
        }

        if (t instanceof IdValue) {
            String id = ((IdValue) t).getIdentifier();
            if (map.containsKey(id)) {
                return map.get(id).copy(parent);
            }
            return t.copy(parent);
        }

        if (t instanceof FieldTerm) {
            FieldTerm ft = (FieldTerm) t;
            String field = ft.getField();
            Term owner = ft.getOwner();
            
            if (owner instanceof IdValue) {
                String ownerId = ((IdValue) owner).getIdentifier();
                String key = ownerId + "." + field;
                if (map.containsKey(key)) {
                    return map.get(key).copy(parent);
                }
            }
            
            FieldTerm newFt = new FieldTerm();
            newFt.setParent(parent);
            newFt.setField(field);
            newFt.setOwner(Scheduler.rewriteTerm(owner, map, newFt));
            return newFt;
        }

        if (t instanceof BinaryOperatorTerm) {
            BinaryOperatorTerm bt = (BinaryOperatorTerm) t;
            BinaryOperatorTerm newBt = new BinaryOperatorTerm();
            newBt.setParent(parent);
            newBt.setOpr(bt.getOpr());
            newBt.setLeft(Scheduler.rewriteTerm(bt.getLeft(), map, newBt));
            newBt.setRight(Scheduler.rewriteTerm(bt.getRight(), map, newBt));
            return newBt;
        }

        if (t instanceof SingleOperatorTerm) {
            SingleOperatorTerm st = (SingleOperatorTerm) t;
            SingleOperatorTerm newSt = new SingleOperatorTerm();
            newSt.setParent(parent);
            newSt.setOpr(st.getOpr());
            newSt.setTerm(Scheduler.rewriteTerm(st.getTerm(), map, newSt));
            return newSt;
        }
        
        if (t instanceof IteTerm) {
            IteTerm it = (IteTerm) t;
            IteTerm newIt = new IteTerm();
            newIt.setParent(parent);
            newIt.setCondition(Scheduler.rewriteTerm(it.getCondition(), map, newIt));
            newIt.setThenTerm(Scheduler.rewriteTerm(it.getThenTerm(), map, newIt));
            newIt.setElseTerm(Scheduler.rewriteTerm(it.getElseTerm(), map, newIt));
            return newIt;
        }

        return t.copy(parent);
    }
}