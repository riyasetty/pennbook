package edu.upenn.cis.nets212.news;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.hadoop.hdfs.server.namenode.FsImageProto.NameSystemSectionOrBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.jackson.ContextDataAsEntryListDeserializer;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

import com.amazonaws.services.dynamodbv2.document.BatchWriteItemOutcome;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.ItemCollection;
import com.amazonaws.services.dynamodbv2.document.KeyAttribute;
import com.amazonaws.services.dynamodbv2.document.PrimaryKey;
import com.amazonaws.services.dynamodbv2.document.QueryOutcome;
import com.amazonaws.services.dynamodbv2.document.ScanOutcome;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.TableWriteItems;
import com.amazonaws.services.dynamodbv2.document.spec.QuerySpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.amazonaws.services.dynamodbv2.model.ScanResult;

import edu.upenn.cis.nets212.config.Config;
import edu.upenn.cis.nets212.news.livy.Node;
import edu.upenn.cis.nets212.storage.DynamoConnector;
import edu.upenn.cis.nets212.storage.SparkConnector;
import scala.Tuple2;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;




public class BuildGraph {
	/**
	 * The basic logger
	 */
	static Logger logger = LogManager.getLogger(BuildGraph.class);

	/**
	 * Connection to Apache Spark
	 */
	SparkSession spark;
	DynamoDB db;
	private transient JavaSparkContext context;
	Table users;
	Table categories;
	Table articles;
	Table friends;
	Table article_likes;
	Table article_recommendations;
	//replace with today''s date
	final String DATE = "2021-12-21";
	HashMap<String, String> articleToDate = new HashMap<String, String>();
	List<String> testList = new LinkedList<String>();

	public BuildGraph() {
		System.setProperty("file.encoding", "UTF-8");
	}

	/**
	 * Initialize the database connection and open the file
	 * 
	 * @throws IOException
	 * @throws InterruptedException
	 */
	public void initialize() throws IOException, InterruptedException {
		logger.info("Connecting to Spark...");
		
		spark = SparkConnector.getSparkConnection();
		context = SparkConnector.getSparkContext();
		db = DynamoConnector.getConnection(Config.DYNAMODB_URL);
		logger.debug("Connected!");
		users = db.getTable("users");
		categories = db.getTable("categories");
		articles = db.getTable("articles");
		friends = db.getTable("friends");
		article_likes = db.getTable("article_likes");
		
		initializeTables();
	}
	
	private void initializeTables() throws DynamoDbException, InterruptedException {
		try {
			article_recommendations = db.createTable("article_recommendations", Arrays.asList(new KeySchemaElement("username", KeyType.HASH)), // Partition
																												// key
					Arrays.asList(new AttributeDefinition("username", ScalarAttributeType.S)),
					new ProvisionedThroughput(25L, 25L)); // Stay within the free tier

			article_recommendations.waitForActive();
		} catch (final ResourceInUseException exists) {
			article_recommendations = db.getTable("article_recommendations");
		}

	}
	
	
	private Tuple2<JavaRDD<String>, JavaPairRDD<String, String>> getNodesAndEdgeSet() {
		
		ItemCollection<ScanOutcome> articleCol = articles.scan();
		ItemCollection<ScanOutcome> userCol = users.scan();
		ItemCollection<ScanOutcome> categoryCol = categories.scan();
		ItemCollection<ScanOutcome> friendsCol = friends.scan();
		ItemCollection<ScanOutcome> likesCol = article_likes.scan();
		
		List<Tuple2<String, String>> edgeStrings = new LinkedList<Tuple2<String, String>>();
		LinkedList<String> nodeStrings = new LinkedList<String>();
		
		
		articleCol.pages().forEach(p -> p.iterator().forEachRemaining(q -> {
			//if (q.getString("published_date").equals(DATE)) {
			nodeStrings.add("a" + String.valueOf(q.get("article_id")));
			edgeStrings.add(new Tuple2<String, String>(nodeStrings.getLast(), 
					q.getString("category")));
			String currentDate = q.getString("published_date");
			if (currentDate.equals(DATE)) {
				articleToDate.put(nodeStrings.getLast(), currentDate);
				testList.add(nodeStrings.getLast());
			}
		}));
		
		userCol.pages().forEach(p -> p.iterator().forEachRemaining(q -> {
			nodeStrings.add("u" + String.valueOf(q.get("username")));
			Set<String> interests = q.getStringSet("topics");
			for (String interest : interests) {
				edgeStrings.add(new Tuple2<String, String>(nodeStrings.getLast(), interest.toUpperCase()));
			}
		}));
		
		categoryCol.pages().forEach(p -> p.iterator().forEachRemaining(q -> {
			nodeStrings.add(String.valueOf(q.get("type")));
		}));
		
		friendsCol.pages().forEach(p -> p.iterator().forEachRemaining(q -> {
			if (q.getBoolean("accepted")) {
				edgeStrings.add(new Tuple2<String, String>("u" + q.getString("friend1"), "u" + q.getString("friend2")));
			}
		}));
		
		likesCol.pages().forEach(p -> p.iterator().forEachRemaining(q -> {
			edgeStrings.add(new Tuple2<String, String>("a" + String.valueOf(q.getNumber("article_id")), "u" + q.getString("username")));
		}));

		JavaRDD<String> nodes = context.parallelize(nodeStrings);
		
		JavaRDD<Tuple2<String, String>> edgesRDD = context.parallelize(edgeStrings);
		JavaPairRDD<String, String> edges = edgesRDD.mapToPair(p -> new Tuple2<String, String>(p._1, p._2));
		Tuple2<JavaRDD<String>, JavaPairRDD<String, String>> nodesAndEdges = 
				new Tuple2<JavaRDD<String>, JavaPairRDD<String, String>>(nodes, edges);
		return nodesAndEdges;
	}

	private JavaPairRDD<String, String> addBackEdges(JavaPairRDD<String, String> edges,
			JavaRDD<String> sinkNodes) {
		JavaPairRDD<String, String> sinks = sinkNodes.mapToPair(p -> new Tuple2<>(p, p));
		//joins network to sinks to get a PairRDD of all the edges of sink nodes
		JavaPairRDD<String, Tuple2<String, String>> edgeNodes = edges.join(sinks);
		//flips the edges and then joins them to the network to add the back edges
		JavaPairRDD<String, String> backEdges = edgeNodes.mapToPair(p -> 
			new Tuple2<String, String>(p._2._1, p._1));
		return edges.union(backEdges);
	}


	private JavaPairRDD<Node, List<Tuple2<Node, Double>>> runAdsorption(JavaRDD<String> nodeStrings, JavaPairRDD<String, String> edges) {
		
		//initialize edge weights for each node
		// for each node, find # of outgoing edges, give edge weight based on first letter "u", "a" or else 
		//divided by total number of outgoing edges
		int i = 0;
		double largestChange = (double) Integer.MAX_VALUE;
		
		JavaPairRDD<String, Node> nodes = nodeStrings.mapToPair(p -> new Tuple2<>(p, new Node(p)));
		
		JavaPairRDD<Tuple2<String, String>, Node> nodesThroughIncEdges = edges.join(nodes).mapToPair(p -> 
			new Tuple2<>(new Tuple2<>(p._1, p._2._1), p._2._2));
		JavaPairRDD<Tuple2<String, String>, Node> nodesThroughOutEdges = edges.mapToPair(p -> 
			new Tuple2<>(p._2, p._1)).join(nodes).mapToPair(p -> new Tuple2<>(new Tuple2<>(p._2._1, p._1), p._2._2));
		JavaPairRDD<Node, Node> nodeEdges = nodesThroughIncEdges.join(nodesThroughOutEdges).mapToPair(p ->
				new Tuple2<>(p._2._1, p._2._2));
		
		JavaPairRDD<Tuple2<Node, String>, Node> edgesWithType = nodeEdges.mapToPair(p -> {
			String s = "";
			if (p._1.isArticle()) {
				s = "x";
			} else if (p._2.isUser()) {
				s = "u";
			} else if (p._2.isArticle()) {
				s = "a";
			} else {
				s = "c";
			}
			return new Tuple2<>(new Tuple2<>(p._1, s), p._2);
		});
		JavaPairRDD<Node, String> mappedToType = edgesWithType.mapToPair(p -> p._1);
		JavaPairRDD<Tuple2<Node, String>, Integer> followedCount = mappedToType.mapToPair(p -> new Tuple2<>(p, 1));
		JavaPairRDD<Tuple2<Node, String>, Integer> nodeTransferCount = followedCount.reduceByKey((a, b) -> a + b);
		JavaPairRDD<Tuple2<Node, String>, Double> weightForType = nodeTransferCount.mapToPair(p -> {
			Double factor = 0.60;
			if (p._1._1.isArticle()) {
				factor = 1.00;
			} else if (!p._1._1.isUser()) {
				factor = 0.50;
			} else if (p._1._2.charAt(0) == 'a') {
				factor = 0.40;
			}
			return new Tuple2<>(p._1, factor / p._2);
		});
		JavaPairRDD<Tuple2<Node, String>, Tuple2<Node, Double>> intermediate = edgesWithType.join(weightForType);

		// <<v1, v2>, weight>
		JavaPairRDD<Tuple2<Node, Node>, Double> weightedGraph = intermediate
				.mapToPair(p -> new Tuple2<>(new Tuple2<>(p._1._1, p._2._1), p._2._2));

		//<v2, <v1, weight>>
		JavaPairRDD<Node, Tuple2<Node, Double>> weightedJoinInc = weightedGraph
				.mapToPair(p -> new Tuple2<>(p._1._2, new Tuple2<>(p._1._1, p._2)));
		
		JavaRDD<Node> incompleteNodes = weightedJoinInc.groupByKey().map(p -> {
			Iterator<Tuple2<Node, Double>> it = p._2.iterator();
			while (it.hasNext()) {
				p._1.addIncEdgeWeight(it.next());
			}
			return p._1;
		});
		
		

		List<Node> oldUsers = incompleteNodes.filter(p -> p.isUser()).collect();
		
		//incoming edges and weights, outgoing edges and weights, label weights
		JavaRDD<Node> completeNodes = incompleteNodes.map(p -> {
			p.addStartingWeights(oldUsers);
			return p;
		});
		
		//<current v2, <v2, <oldv1, weight>>
		JavaPairRDD<Node, Tuple2<Node, Tuple2<Node, Double>>> updatedEdges = completeNodes.mapToPair(p -> new Tuple2<>(p, p))
				.join(weightedJoinInc);
		
		
		//v2 user weights
		JavaPairRDD<Node, List<Tuple2<Node, Double>>> userWeightRDD = completeNodes.mapToPair(p -> new Tuple2<>(p, p.getLabelSetList())).sortByKey();
		
		//on flipped? v2, edgeweight v1
		JavaPairRDD<Node, Tuple2<Double, Node>> edgeTransferRDD = updatedEdges.mapToPair(p -> {
			return new Tuple2<>(p._1, new Tuple2<>(p._2._2._2, p._2._2._1));
		});
		JavaPairRDD<Node, List<Tuple2<Node, Double>>> propegate;
		JavaPairRDD<Node, List<Tuple2<Node, Double>>> oldUserWeights = userWeightRDD;
		JavaPairRDD<Double, Node> change;
		JavaPairRDD<Node, Tuple2<Node, List<Tuple2<Node, Double>>>> incomingWeightRDD;
		while (i < 15 && largestChange >= 0.001) {
			//want: v2, v1, v1userweights
			incomingWeightRDD = weightedGraph.mapToPair(p ->
					new Tuple2<>(p._1._2, p._1._1)).join(userWeightRDD).mapToPair(p -> 
						new Tuple2<>(p._2._1, new Tuple2<>(p._1, p._2._2)));
			
			propegate = edgeTransferRDD.join(incomingWeightRDD).mapToPair(p -> {
				List<Tuple2<Node, Double>> v2Weights = p._2._2._2;
				for (int j = 0; j < v2Weights.size(); j++) {
					Tuple2<Node, Double> userWeight = p._2._2._2.get(j);
					Double newWeight = userWeight._2 * p._2._1._1;
					v2Weights.set(j, new Tuple2<>(userWeight._1, newWeight));
				}
				return new Tuple2<>(p._1, v2Weights);
				
			});
			
			System.out.println("DONE WITH PROPEGATION");
			
			userWeightRDD = propegate.reduceByKey((a,b) -> {
				for (int j = 0; j < a.size(); j++) {
					Tuple2<Node, Double> aVal = a.get(j);
					Tuple2<Node, Double> bVal = b.get(j);
					a.set(j, new Tuple2<>(aVal._1, aVal._2 + bVal._2));
				}
				return a;
			});
			
			System.out.println("DONE WITH COMBINING WEIGHTS FOR SAME KEYS");
			
			//reset each user's self weight to 1
			userWeightRDD = userWeightRDD.mapToPair(p -> {
				if (p._1.isUser()) {
					for (int j = 0; j < p._2.size(); j++) {
						Tuple2<Node, Double> curr = p._2.get(j);
						if (curr._1.equals(p._1)) {
							p._2.set(j, new Tuple2<>(curr._1, 1.00));
						}
					}
				}
				return p;
			});
			//normalization step
			//iterate through list, get some of all user weights, scale to 1, recalculate
			userWeightRDD = userWeightRDD.mapToPair(p -> {
				Double total = 0.00;
				for (Tuple2<Node, Double> user : p._2) {
					total += user._2;
				}
				
				Double scale;
				if (total > 0) {
					scale = 1.00 / total;
				} else {
					scale = 1.00;
				}
				for (int j = 0; j < p._2.size(); j++) {
					Tuple2<Node, Double> curr = p._2.get(j);
					p._2.set(j, new Tuple2<>(curr._1, curr._2 * scale));
				}
				
				return p;
			});
			
			change = oldUserWeights.join(userWeightRDD).mapToPair(p -> {
				Double largestInnerChange = 0.00;
				for (int j = 0; j < p._2._1.size(); j++) {
					
					Double currentInnerChange = Math.abs(p._2._1.get(j)._2 - p._2._2.get(j)._2);
					if (currentInnerChange > largestInnerChange) {
						largestInnerChange = currentInnerChange;
					}
				}
				return new Tuple2<>(largestInnerChange, p._1);
			}).sortByKey(false);
			
			Double currentChange = change.first()._1;
			System.out.println("LARGEST CHANGE: " + currentChange);
			// checking if current change is less than the previous change to switch it
			if (currentChange < largestChange) {
				largestChange = currentChange;
			}
			// iterate and update the old rank to the current one
			i++;
			oldUserWeights = userWeightRDD;
			System.out.println("Iteration: " + i);
		}
		//return null;
		return userWeightRDD;
	}

	/**
	 * Main functionality in the program: read and process the social network
	 * @param object 
	 * @param iMax 
	 * @param dMax 
	 * 
	 * @throws IOException          File read, network, and other errors
	 * @throws InterruptedException User presses Ctrl-C
	 */
	public void run() throws IOException, InterruptedException {
		logger.info("Running");

		Tuple2<JavaRDD<String>, JavaPairRDD<String, String>> nodesAndEdges = getNodesAndEdgeSet();
		JavaRDD<String> nodeStrings = nodesAndEdges._1;
		JavaPairRDD<String, String> edges = nodesAndEdges._2;
		
		JavaRDD<String> sinkNodes = nodeStrings.subtract(edges.values());
		JavaPairRDD<String, String> graph = addBackEdges(edges, sinkNodes);
		
		JavaPairRDD<Node, List<Tuple2<Node, Double>>> nodes = runAdsorption(nodeStrings, graph);
		System.out.println("NODES SIZE: " + nodes.count());
		JavaPairRDD<Node, List<Tuple2<Node, Double>>> articleRDD = nodes.filter(p -> p._1.isArticle());
		
		Map<String, String> map = articleToDate;
		JavaRDD<Node> todayArticles = articleRDD.filter(p -> map.get(p._1.getVal()) != null).map(p ->{
			p._1.updateUserWeights(p._2);
			return p._1;
		});
		//JavaRDD<Node> todayArticles = articleRDD.filter(p -> testList.contains(p.getVal()));
		
		System.out.println("TODAY ARTICLES: " + todayArticles.count());
		
		
		
		JavaPairRDD<Node, List<Tuple2<Node, Double>>> userRDD = nodes.filter(p -> p._1.isUser());
		JavaRDD<Node> userWithWeight = userRDD.map(p -> {
			p._1.updateUserWeights(p._2);
			return p._1;
		});
		
		List<Node> userNodes = userWithWeight.collect();
		List<Tuple2<String, List<List<Object>>>> recommendations = 
				new LinkedList<Tuple2<String, List<List<Object>>>>();
		for (Node user : userNodes) {
			JavaRDD<List<Object>> sortedArticles = todayArticles.mapToPair(p -> 
				new Tuple2<>(p.getUserWeight(user), p.getVal())).sortByKey(false).map(p -> {
					LinkedList<Object> l = new LinkedList<Object>();
					l.add(p._2);
					if (p._1 > 0.0001) {
						l.add(p._1);
					}
					return l;
				});

			List<List<Object>> listRecs = sortedArticles.collect();
			System.out.println("RECS FOR " + user.getVal() + " :" + listRecs);
			recommendations.add(new Tuple2<>(user.getVal(), listRecs));
		}
		
		
		LinkedList<Item> items = writeRow(recommendations);
		//return;
		BatchWriteItemOutcome outcome = null;
		//iterates through the list of items
		int size = 0;
		while(!items.isEmpty()) {
			TableWriteItems table = new TableWriteItems("article_recommendations");
			HashSet<Item> toAdd = new HashSet<Item>();
			//fills a set of up to 25 keywords
			for (int i = 0; i < 25; i++) {
				if(!items.isEmpty()) {
					toAdd.add(items.removeFirst());
					size++;
				}
			}
			//adds the set to the table then writes it
			if (!toAdd.isEmpty()) {
				table.withItemsToPut(toAdd);
				outcome = db.batchWriteItem(table);
			}
		}
		
	}

	static LinkedList<Item> writeRow(List<Tuple2<String, List<List<Object>>>> recs) {
		//collects the RDD into a list of rows
		LinkedList<Item> items = new LinkedList<Item>();
		//iterates through rows and creates an item for each row
		for (Tuple2<String, List<List<Object>>> t : recs) {
			Item item = new Item()
					.withPrimaryKey("username", t._1.substring(1))
					.withList("recommendations", t._2);
			items.add(item);
		}
		return items;
	}
	
	
	
	/**
	 * Graceful shutdown
	 */
	public void shutdown() {
		logger.info("Shutting down");

		if (spark != null)
			spark.close();
	}

	public static void main(String[] args) {
		final BuildGraph bg = new BuildGraph();

		try {
			bg.initialize();

			bg.run();
		} catch (final IOException ie) {
			logger.error("I/O error: ");
			ie.printStackTrace();
		} catch (final InterruptedException e) {
			e.printStackTrace();
		} finally {
			bg.shutdown();
		}
	}

}
