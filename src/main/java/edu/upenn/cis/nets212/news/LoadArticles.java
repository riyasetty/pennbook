package edu.upenn.cis.nets212.news;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.hadoop.mapred.lib.db.DBWritable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema;
import org.apache.spark.sql.types.StructType;

import com.amazonaws.services.dynamodbv2.document.BatchWriteItemOutcome;
import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.document.TableWriteItems;
import com.amazonaws.services.dynamodbv2.document.UpdateItemOutcome;
import com.amazonaws.services.dynamodbv2.document.spec.UpdateItemSpec;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceInUseException;
import com.amazonaws.services.dynamodbv2.model.ReturnValue;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.opencsv.CSVParser;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;

import edu.upenn.cis.nets212.config.Config;
import edu.upenn.cis.nets212.storage.DynamoConnector;
import edu.upenn.cis.nets212.storage.SparkConnector;
import opennlp.tools.stemmer.PorterStemmer;
import opennlp.tools.stemmer.Stemmer;
import opennlp.tools.tokenize.SimpleTokenizer;
import scala.Tuple2;
import software.amazon.awssdk.services.dynamodb.model.DynamoDbException;

public class LoadArticles {
	/**
	 * The basic logger
	 */
	static Logger logger = LogManager.getLogger(LoadArticles.class);

	/**
	 * Connection to DynamoDB
	 */
	DynamoDB db;
	Table articles;
	Table articles_index;
	
	CSVParser parser;
	
	SimpleTokenizer model;
	Stemmer stemmer;
	/**
	 * Connection to Apache Spark
	 */
	SparkSession spark;
	
	JavaSparkContext context;
	
	static int articleID = 0;
	
	public LoadArticles() {
		System.setProperty("file.encoding", "UTF-8");
		parser = new CSVParser();
		model = SimpleTokenizer.INSTANCE;
		stemmer = new PorterStemmer();
	}
	
	private void initializeTables() throws DynamoDbException, InterruptedException {
		try {
			articles = db.createTable("articles", Arrays.asList(new KeySchemaElement("article_id", KeyType.HASH), // Partition
																												// key
					new KeySchemaElement("published_date", KeyType.RANGE)),
					Arrays.asList(new AttributeDefinition("article_id", ScalarAttributeType.N),
							new AttributeDefinition("published_date", ScalarAttributeType.S)),
					new ProvisionedThroughput(100L, 100L)); // No More Free Tier!

			articles.waitForActive();
		} catch (final ResourceInUseException exists) {
			articles = db.getTable("articles");
		}
		try {
			articles_index = db.createTable("articles_index", Arrays.asList(new KeySchemaElement("keyword", KeyType.HASH), // Partition
																												// key
					new KeySchemaElement("article_id", KeyType.RANGE)), // Sort key
					Arrays.asList(new AttributeDefinition("keyword", ScalarAttributeType.S),
							new AttributeDefinition("article_id", ScalarAttributeType.N)),
					new ProvisionedThroughput(100L, 100L));

			articles_index.waitForActive();
		} catch (final ResourceInUseException exists) {
			articles_index = db.getTable("articles_index");
		}

	}
	
	/**
	 * Initialize the database connection and open the file
	 * 
	 * @throws IOException
	 * @throws InterruptedException 
	 * @throws DynamoDbException 
	 */
	public void initialize() throws IOException, DynamoDbException, InterruptedException {
		logger.info("Connecting to DynamoDB...");
		db = DynamoConnector.getConnection(Config.DYNAMODB_URL);
		
		spark = SparkConnector.getSparkConnection();
		context = SparkConnector.getSparkContext();
		
		initializeTables();
		
		logger.debug("Connected!");
	}
	
	/**
	 * Returns an RDD of parsed talk data
	 * 
	 * @param filePath
	 * @return
	 * @throws IOException
	 */
	Tuple2<JavaRDD<Row>, List<Item>> getArticles(String filePath) throws IOException {
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
		CSVReader reader = null;
		Reader fil = null;
		List<Article> listOfArticles = new LinkedList<Article>(); 
		
		int x =0;
		try {
			fil = new BufferedReader(new FileReader(new File(filePath)));
			reader = new CSVReader(fil);
			String[] nextLine = null;
			try {
				do {
					try {
						//reads the line into a list of lines
						nextLine = reader.readNext();
						if (nextLine != null && x < 100) {
							//JSON to Java Object
							String headlineFix = mapper.writeValueAsString(nextLine[1].substring(12));
							nextLine[1] = nextLine[1].substring(0, 11) +
									headlineFix.substring(0, headlineFix.length()-1);
							String articleFix = mapper.writeValueAsString(nextLine[1].substring(11));
							nextLine[2] = nextLine[2].substring(0, 10) +
									articleFix.substring(0, articleFix.length()-1);
							String sdFix =mapper.writeValueAsString(nextLine[4].substring(21));
							nextLine[4] = nextLine[4].substring(0, 20) + 
									sdFix.substring(0, sdFix.length()-1);
							String readLine = String.join("\", \"", nextLine);
							StringBuilder sb = new StringBuilder(readLine);
							sb.insert(1, "\"");
							String line = sb.toString();
				            Article articleVal = mapper.readValue(line, Article.class);
				            String jsonInString2 = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(articleVal);
				            System.out.println(jsonInString2);
							listOfArticles.add(articleVal);
							x++;
							System.out.println("New x: " + x);
						}
					} catch (CsvValidationException e) {
						e.printStackTrace();
					}					
				} while (nextLine != null);
			} catch (IOException e) {
				e.printStackTrace();
			}
		} finally {
			if (reader != null)
				reader.close();
			
			if (fil != null)
				fil.close();
		}
		//establishes the structure type of rows
		StructType rowSchema = new StructType()
				.add("article_id", "int")
				.add("category", "string")
				.add("headline", "string")
				.add("authors", "string")
				.add("link", "string")
				.add("short_description", "string")
				.add("date", "string");
		
		//turns the list into a stream of string arrays
		Stream<Article> streamOfStartRows = listOfArticles.stream();
		//maps each array into a row to create stream of rows
		Stream<Row> streamOfRows = streamOfStartRows.map(line -> createRow(line, rowSchema));
		//collects the stream to become a list
		List<Row> listOfRows = streamOfRows.collect(Collectors.toList());
		//parallelizes the list into an RDD
		List<Item> keywords = getKeywords(listOfRows);
		System.out.println("WORDS TO BE ADDED:" + keywords.size());
		JavaRDD<Row> readRows = context.parallelize(listOfRows);
    	return new Tuple2<JavaRDD<Row>, List<Item>>(readRows, keywords);
	}
	
	static class Article {
		private String category;
		private String headline;
		private String authors; 
		private String link;
		private String short_description;
		private String date;

		public Article() {
			
		}
		
		public Article(String category, String headline, String authors, String link,
				String short_description, String date) {
			this.category = category;
			this.headline = headline;
			this.authors = authors;
			this.link = link;
			this.short_description = short_description;
			this.date = date;
		}

		public String getCategory() {
			return category;
		}

		public void setCategory(String category) {
			this.category = category;
		}

		public String getHeadline() {
			return headline;
		}

		public void setHeadline(String headline) {
			this.headline = headline;
		}

		public String getAuthors() {
			return authors;
		}

		public void setAuthors(String authors) {
			this.authors = authors;
		}

		public String getLink() {
			return link;
		}

		public void setLink(String link) {
			this.link = link;
		}

		public String getShort_description() {
			return short_description;
		}

		public void setShort_description(String short_description) {
			this.short_description = short_description ;
		}

		public String getDate() {
			return date;
		}

		public void setDate(String date) {
			this.date = date;
		}
		
		// getters , setters, some boring stuff
	}
	
	
	HashSet<String> readStopWords() throws IOException {
		System.out.println("READING STOP WORDS");
		HashSet<String> stopWords = new HashSet<String>();
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(new File(Config.STOP_WORDS)));
			String line = null;
			do {
				try {
					line = reader.readLine();
					if (line != null) {
						System.out.println("Stop Word: " + line);
						stopWords.add(line);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			} while (line != null);
		} finally {
			if (reader != null)
				reader.close();
		}
		
		return stopWords;
	}
	
	List<Item> getKeywords(List<Row> listOfRows) throws IOException {
		System.out.println("READING KEY WORDS");
		List<Item> keywords = new LinkedList<Item>();
		HashSet<String> stopWords = readStopWords();
		for (Row row : listOfRows) {
			String headline = (String) row.get(2);
			String[] words = model.tokenize(headline);
			//iterate through each word in the row
			for (String word : words) {
				//check if the word is only letter
				if (word.matches("[a-zA-Z]+")) {
					//make the word lower case
					word = word.toLowerCase();
					boolean add = true;
					//check if the word is a stop word
					if (stopWords.contains(word)) {
						add = false;
					}
					//stem the word
					word = ((String) stemmer.stem(word));
					//once we have simplified the word we make sure its not an
					//empty string and that it's not already in our list of keywords
					//and then we add it to the list
					if (add && !word.equals("")) {
						System.out.println("Word being added: " + word);
						Item item = new Item()
								.withPrimaryKey("keyword", word)
								.withInt("article_id", (int) row.get(0));
						
						keywords.add(item);
					}
				}
			}
		}
		return keywords;
	}
	
	Row createRow(Article values, StructType schema) {
		Object[] row = new Object[7];
		//iterates through the row and updates the value depending on the column value
		row[0] = articleID;
		articleID++;
		row[1] = values.getCategory();
		row[2] = values.getHeadline();
		row[3] = values.getAuthors();
		row[4] = values.getLink();
		row[5] = values.getShort_description();
		String oldDate = values.getDate();
		String oldYear = oldDate.substring(2, 4);
		int newYear = Integer.parseInt(oldYear) + 4;
		String newDate = oldDate.substring(0, 2) + Integer.toString(newYear) + oldDate.substring(4);
		row[6] = newDate;
		return new GenericRowWithSchema(row, schema);	
	}
	
	static LinkedList<Item> writeRow(JavaRDD<Row> articleVals) {
		//collects the RDD into a list of rows
		List<Row> listOfArticles = articleVals.collect();
		LinkedList<Item> items = new LinkedList<Item>();
		//iterates through rows and creates an item for each row
		for (Row r : listOfArticles) {
			Item item = new Item()
					.withPrimaryKey("article_id", r.get(0))
					.withString("published_date",(String) r.get(6))
					.withString("category", (String) r.get(1))
					.withString("headline", (String) r.get(2))
					.withString("authors", (String) r.get(3))
					.withString("link", (String) r.get(4))
					.withString("short_description", (String) r.get(5))
					.withList("liked_by", new LinkedList<String>());
					
			items.add(item);
		}
		return items;
	}
	
	/**
	 * Main functionality in the program: read and process the social network
	 * 
	 * @throws IOException File read, network, and other errors
	 * @throws DynamoDbException DynamoDB is unhappy with something
	 * @throws InterruptedException User presses Ctrl-C
	 */
	public void run() throws IOException, DynamoDbException, InterruptedException {
		logger.info("Running");

		// Load + store the Articles
		Tuple2<JavaRDD<Row>, List<Item>> tuple = this.getArticles(Config.ARTICLE_PATH);
		JavaRDD<Row> articleVals = tuple._1;
		LinkedList<Item> keywordItems = (LinkedList<Item>) tuple._2;
		LinkedList<Item> items = writeRow(articleVals);
		/*
		BatchWriteItemOutcome outcome = null;
		//iterates through the list of items
		int size = 0;
		while(!items.isEmpty()) {
			TableWriteItems table = new TableWriteItems("articles");
			HashSet<Item> toAdd = new HashSet<Item>();
			//fills a set of up to 25 keywords
			for (int i = 0; i < 25; i++) {
				if(!items.isEmpty()) {
					toAdd.add(items.removeFirst());
					size++;
					System.out.println(size);
				}
			}
			//adds the set to the table then writes it
			if (!toAdd.isEmpty()) {
				table.withItemsToPut(toAdd);
				outcome = db.batchWriteItem(table);
				System.out.println("writing");
			}
		}
		System.out.println("FINISHED ADDING ARTICLES");
		System.out.println("Number of Articles Added:" + size);
		*/
		BatchWriteItemOutcome outcome2 = null;
		int keywordSize = 0;
		while(!keywordItems.isEmpty()) {
			TableWriteItems table2 = new TableWriteItems("articles_index");
			HashSet<Item> toAdd2 = new HashSet<Item>();
			//fills a set of up to 25 keywords
			for (int i = 0; i < 25; i++) {
				if(!keywordItems.isEmpty()) {
					toAdd2.add(keywordItems.removeFirst());
					keywordSize++;
					System.out.println(keywordSize);
				}
			}
			//adds the set to the table then writes it
			if (!toAdd2.isEmpty()) {
				table2.withItemsToPut(toAdd2);
				outcome2 = db.batchWriteItem(table2);
				System.out.println("writing");
			}
		}
		
		System.out.println("FINISHED ADDING KEYWORDS");
		System.out.println("Number of Keywords Added:" + keywordSize);
	}
	
	/**
	 * Graceful shutdown
	 */
	public void shutdown() {
		logger.info("Shutting down");
		
		DynamoConnector.shutdown();
		
		if (spark != null)
			spark.close();
	}
	
	public static void main(String[] args) {
		final LoadArticles ln = new LoadArticles();

		try {
			ln.initialize();

			ln.run();
		} catch (final IOException ie) {
			logger.error("I/O error: ");
			ie.printStackTrace();
		} catch (final DynamoDbException e) {
			e.printStackTrace();
		} catch (final InterruptedException e) {
			e.printStackTrace();
		} finally {
			ln.shutdown();
		}
	}

}
