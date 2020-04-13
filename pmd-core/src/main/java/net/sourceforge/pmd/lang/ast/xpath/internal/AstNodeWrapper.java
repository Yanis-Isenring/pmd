/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.ast.xpath.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.sourceforge.pmd.lang.ast.Node;
import net.sourceforge.pmd.lang.ast.xpath.Attribute;
import net.sourceforge.pmd.util.CollectionUtil;

import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.pattern.NameTest;
import net.sf.saxon.pattern.NodeTest;
import net.sf.saxon.tree.iter.AxisIterator;
import net.sf.saxon.tree.iter.EmptyIterator;
import net.sf.saxon.tree.iter.ListIterator;
import net.sf.saxon.tree.iter.ListIterator.OfNodes;
import net.sf.saxon.tree.iter.LookaheadIterator;
import net.sf.saxon.tree.iter.ReverseListIterator;
import net.sf.saxon.tree.iter.SingleNodeIterator;
import net.sf.saxon.tree.util.FastStringBuffer;
import net.sf.saxon.tree.util.Navigator.AxisFilter;
import net.sf.saxon.tree.wrapper.AbstractNodeWrapper;
import net.sf.saxon.type.Type;


/**
 * A wrapper for Saxon around a Node.
 */
public final class AstNodeWrapper extends AbstractNodeWrapper {

    private final AstNodeWrapper parent;
    private final Node wrappedNode;
    private final int id;

    private final List<AstNodeWrapper> children;
    private final Map<String, AstAttributeWrapper> attributes;


    public AstNodeWrapper(AstDocument document,
                          IdGenerator idGenerator,
                          AstNodeWrapper parent,
                          Node wrappedNode) {
        this.treeInfo = document;
        this.parent = parent;
        this.wrappedNode = wrappedNode;
        this.id = idGenerator.getNextId();

        this.children = new ArrayList<>(wrappedNode.getNumChildren());

        for (int i = 0; i < wrappedNode.getNumChildren(); i++) {
            children.add(new AstNodeWrapper(document, idGenerator, this, wrappedNode.getChild(i)));
        }

        Map<String, AstAttributeWrapper> atts = new HashMap<>();
        Iterator<Attribute> it = wrappedNode.getXPathAttributesIterator();

        while (it.hasNext()) {
            Attribute next = it.next();
            atts.put(next.getName(), new AstAttributeWrapper(this, next));
        }

        this.attributes = atts;
    }


    @Override
    public AstDocument getTreeInfo() {
        return (AstDocument) super.getTreeInfo();
    }

    List<AstNodeWrapper> getChildren() {
        return children;
    }

    @Override
    public Node getUnderlyingNode() {
        return wrappedNode;
    }


    @Override
    public int getColumnNumber() {
        return wrappedNode.getBeginColumn();
    }


    @Override
    public int compareOrder(NodeInfo other) {
        return Integer.compare(id, ((AstNodeWrapper) other).id);
    }

    @Override
    protected AxisIterator iterateAttributes(NodeTest nodeTest) {
        if (nodeTest instanceof NameTest) {
            String local = ((NameTest) nodeTest).getLocalPart();
            return SingleNodeIterator.makeIterator(attributes.get(local));
        }

        return filter(nodeTest, new IteratorAdapter(attributes.values().iterator()));
    }


    @Override
    protected AxisIterator iterateChildren(NodeTest nodeTest) {
        return filter(nodeTest, new OfNodes(children));
    }


    @Override
    protected AxisIterator iterateSiblings(NodeTest nodeTest, boolean forwards) {
        if (parent == null) {
            return EmptyIterator.OfNodes.THE_INSTANCE;
        }

        List<? extends NodeInfo> siblingsList =
            forwards ? CollectionUtil.drop(parent.children, wrappedNode.getIndexInParent())
                     : CollectionUtil.take(parent.children, wrappedNode.getIndexInParent());

        AxisIterator iter =
            forwards ? new ListIterator.OfNodes(siblingsList)
                     : new RevListAxisIterator(siblingsList);

        return filter(nodeTest, iter);
    }

    private static AxisIterator filter(NodeTest nodeTest, AxisIterator iter) {
        return nodeTest != null ? new AxisFilter(iter, nodeTest) : iter;
    }


    @Override
    public String getAttributeValue(String uri, String local) {
        AstAttributeWrapper attributeWrapper = attributes.get(local);

        return attributeWrapper == null ? null : attributeWrapper.getStringValue();
    }


    @Override
    protected AxisIterator iterateDescendants(NodeTest nodeTest, boolean includeSelf) {
        return filter(nodeTest, new DescendantIter(includeSelf));
    }


    @Override
    public int getLineNumber() {
        return wrappedNode.getBeginLine();
    }


    @Override
    public int getNodeKind() {
        return parent == null ? Type.DOCUMENT : Type.ELEMENT;
    }


    @Override
    public NodeInfo getRoot() {
        return getTreeInfo().getRootNode();
    }


    @Override
    public void generateId(FastStringBuffer buffer) {
        buffer.append(Integer.toString(hashCode()));
    }


    public int getId() {
        return id;
    }


    @Override
    public String getLocalPart() {
        return wrappedNode.getXPathNodeName();
    }


    @Override
    public String getURI() {
        return "";
    }


    @Override
    public String getPrefix() {
        return "";
    }


    @Override
    public NodeInfo getParent() {
        return parent;
    }


    @Override
    public CharSequence getStringValueCS() {
        return getStringValue();
    }


    @Override
    public String toString() {
        return "Wrapper[" + getLocalPart() + "]@" + hashCode();
    }

    private class DescendantIter implements AxisIterator, LookaheadIterator {

        private final Deque<AstNodeWrapper> todo;

        public DescendantIter(boolean includeSelf) {
            todo = new ArrayDeque<>();
            if (includeSelf) {
                todo.addLast(AstNodeWrapper.this);
            } else {
                todo.addAll(children);
            }
        }

        @Override
        public boolean hasNext() {
            return !todo.isEmpty();
        }

        @Override
        public NodeInfo next() {
            if (todo.isEmpty()) {
                return null;
            }
            AstNodeWrapper first = todo.getFirst();
            todo.addAll(first.children);
            return first;
        }

        @Override
        public void close() {
            todo.clear();
        }

        @Override
        public int getProperties() {
            return LOOKAHEAD;
        }
    }

    private static class RevListAxisIterator extends ReverseListIterator implements AxisIterator {

        public RevListAxisIterator(List<? extends NodeInfo> list) {
            super(list);
        }

        @Override
        public NodeInfo next() {
            return (NodeInfo) super.next();
        }
    }

    private static class IteratorAdapter implements AxisIterator, LookaheadIterator {

        private final Iterator<? extends NodeInfo> it;

        public IteratorAdapter(Iterator<? extends NodeInfo> it) {
            this.it = it;
        }

        @Override
        public boolean hasNext() {
            return it.hasNext();
        }

        @Override
        public NodeInfo next() {
            return it.hasNext() ? it.next() : null;
        }

        @Override
        public void close() {
            // nothing to do
        }


        @Override
        public int getProperties() {
            return LOOKAHEAD;
        }
    }
}
