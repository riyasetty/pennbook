
package edu.upenn.cis.nets212.news.livy;

import java.io.Serializable;
import java.util.*;

import scala.Tuple2;

public class Node implements Serializable, Comparable<Object> {

	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	String val;
	char type;
	Map<Node, Double> incomingEdgeWeight;
	List<Tuple2<Node, Double>> incomingEdgeWeightList;
	Map<Node, Double> outgoingEdgeWeight;
	List<Tuple2<Node, Double>> outgoingEdgeWeightList;
	Map<Node, Double> labelSet;
	List<Tuple2<Node, Double>> labelSetList;
	boolean test;
	
	public Node(String val) {
		this.val = val;
		this.type = val.charAt(0);
		if (!(this.type == 'u') && !(this.type == 'a')) {
			this.type = 'c';
		}
		incomingEdgeWeight = new HashMap<Node, Double>();
		incomingEdgeWeightList = new LinkedList<Tuple2<Node, Double>>();
		outgoingEdgeWeight = new HashMap<Node, Double>();
		outgoingEdgeWeightList = new LinkedList<Tuple2<Node, Double>>();
		labelSet = new HashMap<Node, Double>();
		labelSetList = new LinkedList<Tuple2<Node, Double>>();
		test = false;
	}
	
	public void addIncEdgeWeight(Tuple2<Node, Double> edge) {
		incomingEdgeWeight.put(edge._1, edge._2);
		incomingEdgeWeightList.add(edge);
	}
	public void addOutEdgeWeight(Tuple2<Node, Double> edge) {
		outgoingEdgeWeight.put(edge._1, edge._2);
		outgoingEdgeWeightList.add(edge);
	}
	
	public void addStartingWeights(List<Node> users) {
		for(Node user : users) {
			if (this.val.equals(user.getVal())) {
				labelSet.put(user, 1.00);
				labelSetList.add(new Tuple2<>(user, 1.00));
			} else {
				labelSet.put(user, 0.00);
				labelSetList.add(new Tuple2<>(user, 0.00));
			}
		}
	}
	
	public boolean isArticle() {
		return this.type == 'a';
	}
	
	public boolean isUser() {
		return this.type == 'u';
	}

	public String getVal() {
		return val;
	}

	public void setVal(String val) {
		this.val = val;
	}

	public List<Tuple2<Node, Double>> getIncomingEdgeWeightList() {
		return incomingEdgeWeightList;
	}
	
	public List<Tuple2<Node, Double>> getOutgoingEdgeWeightList() {
		return outgoingEdgeWeightList;
	}
	/*
	public List<Tuple2<String, Double>> getLabelSetList() {
		return labelSetList;
	}
	*/
	public void setLabelSet(Map<Node, Double> labelSet) {
		this.labelSet = labelSet;
	}

	public void setLabelSetList(List<Tuple2<Node, Double>> labelSetList) {
		this.labelSetList = labelSetList;
	}
	
	public List<Tuple2<Node, Double>> getLabelSetList() {
		return labelSetList;
	}
	
	public Map<Node, Double> getLabelSet() {
		return labelSet;
	}
	public Double getUserWeight(Node user) {
		return labelSet.get(user);
	}
	
	//THIS PART COULD BE AN ISSUE BECAUSE OF THE REFERENCING!!!
	public void updateUserWeights(List<Tuple2<Node, Double>> newWeights) {
		for (Tuple2<Node, Double> w : newWeights) {
			labelSet.replace(w._1, w._2);
		}
	}
	
	public Double getIncomingEdgeWeight(Node n) {
		return incomingEdgeWeight.get(n);
	}
	
	public Double setLabel(Node n, Double w) {
		Double current = labelSet.get(n);
		labelSet.replace(n, w);
		return Math.abs(current - w);
	}
	
	public String toString() {
		return this.val;
	}

	@Override
	public int compareTo(Object o) {
		return val.compareTo(((Node) o).getVal());
	}
	
	@Override
	public boolean equals(Object o) {
		return val.equals(((Node) o).getVal());
	}
	
	@Override
	public int hashCode() {
		return val.hashCode();
	}
	
	public void testing() {
		this.test = true;
	}
	
	public boolean getTest() {
		return test;
	}
	
}
