package uk.ac.bris.cs.scotlandyard.ui.ai;

import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableSet;
import com.google.common.graph.ImmutableValueGraph;
import io.atlassian.fugue.Pair;
import uk.ac.bris.cs.scotlandyard.model.*;
import java.lang.Math;

public class MyAi implements Ai {

	@Nonnull @Override public String name() { return "Mr Nobody"; }

	//Will use to get distance from detective to Mr X
	public class valuesInPQ implements Comparable<valuesInPQ> {
		private final int nodeNumber;
		private int distanceFromSource;
		public valuesInPQ(int node, int dist){
			nodeNumber = node;
			distanceFromSource = dist;
		}
		//Constructor

		private int getNode() {
			return this.nodeNumber;
		}

		private int getDistance(){
			return this.distanceFromSource;
		}

		private void setDistance(int distToSet){
			this.distanceFromSource = distToSet;
		}

		@Override
		public int compareTo(valuesInPQ o) {
			return Integer.compare(this.getDistance(), o.getDistance());
		}
	}
	//Can't have maps in PQ, will create a class implementing comparator

	public valuesInPQ matchNodeToValueInPQ(int node, List<valuesInPQ> dist){
		for(valuesInPQ curr : dist){
			if(curr.getNode() == node){
				return curr;
			}
		} return null; }

	public class TicketVisitor implements Move.Visitor{

		@Override
		public Object visit(Move.SingleMove move) {
			return move.tickets();
		}

		@Override
		public Object visit(Move.DoubleMove move) {
			return move.tickets();
		}

	}

	public class DestinationVisitor implements Move.Visitor {

		@Override
		public Object visit(Move.SingleMove move) {
			return move.destination;
		}

		@Override
		public Object visit(Move.DoubleMove move) {
			return move.destination2;
		}
	}


	public Integer Dijkstra(Integer source, Board board, Player mrX){
		// Retrieves the graph representing the game board, where nodes represent locations and edges represent possible moves between them.
		ImmutableValueGraph<Integer, ImmutableSet<ScotlandYard.Transport>> graph = board.getSetup().graph;

		// Initializes a priority queue to manage nodes based on their shortest path from the source.
		// Nodes with the shortest distances are prioritized.
		PriorityQueue<valuesInPQ> pq = new PriorityQueue<>();

		// A list to keep track of visited nodes to prevent reprocessing.
		List<Integer> visitedNodes = new ArrayList<>();

		// This list holds valuesInPQ objects for each node, which maintain the node number and the shortest distance from the source to that node.
		List<valuesInPQ> currentDistances = new ArrayList<>();

		// Will have all nodes, each with their own current shortest distance

		// Initializes the distance to the source node as 0 and all other nodes as infinity
		// (represented here by Integer.MAX_VALUE).
		for(Integer node : graph.nodes()){

			if(!node.equals(source)) currentDistances.add(new valuesInPQ(node, Integer.MAX_VALUE));
		}
		currentDistances.add(new valuesInPQ(source, 0));
		// The source node is then added to the priority queue.
		pq.add(new valuesInPQ(source, 0));

		// Begins a loop that continues until there are no more nodes to process in the priority queue.
		while(!pq.isEmpty()){
			// Removes and returns the node with the shortest distance from the priority queue.
			valuesInPQ current = pq.poll();
			// Checks if the current node has not been visited to avoid processing the same node multiple times.
				if(!visitedNodes.contains(current.getNode())){

				// Iterates over each node adjacent to the current node.
				for(Integer node : graph.adjacentNodes(current.getNode())){

					// Retrieves the valuesInPQ object for the adjacent node,
					// which contains its current known shortest distance.
					valuesInPQ currAdj = matchNodeToValueInPQ(node, currentDistances);

					//  If the distance to the current node plus one (representing a move to an adjacent node)
					//  is less than the known distance to the adjacent node, update the distance.
					if(current.getDistance() + 1 < currAdj.getDistance()) {
						currAdj.setDistance(current.getDistance() + 1);
					}
					// Adds the adjacent node to the priority queue with the newly calculated distance.
					pq.add(new valuesInPQ(node, current.getDistance() + 1));
				}
				// Marks the current node as visited after all its adjacent nodes have been processed.
				visitedNodes.add(current.getNode());
			}
		}
		// After the loop, retrieves the shortest distance from the source to
		// Mr. X's current location from the list of distances and returns it.
		valuesInPQ mrxValue = matchNodeToValueInPQ(mrX.location(), currentDistances);
		return mrxValue.getDistance();
	}

	public boolean canDetectiveReach(Board board, Integer node){
		List<Piece> detectives = board.getPlayers().stream().filter(t -> t.isDetective()).toList();
		for(Piece det : detectives){
			int detLoc = board.getDetectiveLocation((Piece.Detective) det).orElseThrow();
			if (board.getSetup().graph.adjacentNodes(detLoc).contains(node)) return true;
		}
		return false;
	}

	public double TicketToValueMapCloseDist(ScotlandYard.Ticket ticket){
        return switch (ticket) {
            case BUS, UNDERGROUND -> 0.001;
            default -> 0;
        };
	}
	//Priority getting away, doesn't matter which one mrX uses if he gets away
	//Bus & Underground usually leads to further distance, so slightly prioritises them

	public double TicketToValueMapLongDist(ScotlandYard.Ticket ticket){
        return switch (ticket) {
            case SECRET -> -0.3;
            case DOUBLE -> -0.6;
            default -> 0;
        };
	}
	//If long distance, don't use secret or double, it's a waste

	public double calculateScore(Board board, Player mrX, Move move){
			//Initializes the score to -1. This ensures that any move resulting
			// in a non-negative score is considered better than no move
			double score = -1;
			// Initializes the closest distance to the largest possible integer value.
			// This will be used to find the minimum distance between Mr. X and any detective.
			int closestDistance = Integer.MAX_VALUE;
			// Extracts a list of detectives from the board's list of players
			// by filtering only those pieces that are detectives.
			List<Piece> detectives = board.getPlayers().asList().stream().filter(Piece::isDetective).toList();
			// Iterates over each detective to calculate distances and adjust the score accordingly.
			for(Piece piece : detectives){
				// Casts the Piece to Piece.Detective for further operations that are specific to detective pieces.
				Piece.Detective det = (Piece.Detective) piece;
				// Checks if the location of the detective is known and available.
				if(board.getDetectiveLocation(det).isPresent()){
					// Calculates the shortest path distance from the detective to Mr. X using Dijkstra's algorithm,
					// throwing an exception if the location is somehow null.
					int possDist = Dijkstra(board.getDetectiveLocation(det).orElseThrow(), board, mrX);
					//Updates the score based on the logarithm of the distance
					// (plus one to handle zero distance),
					// scaled by 1.5. Longer distances increase the score logarithmically,
					// implying that moves leading to greater distances from detectives are preferable.
					score = score + 1.5 * Math.log(possDist + 1);
					//Larger distances give higher score, however with smaller difference
					// Updates the closestDistance if the current possDist is smaller, tracking the closest detective to Mr. X
					if(possDist < closestDistance) closestDistance = possDist;
				}
			}
			//Check value of tickets, in close distances it doesn't matter, in large ones don't use secret or double
			// Instantiates a TicketVisitor, a visitor pattern implementation to extract tickets from the move.
			TicketVisitor tickVis = new TicketVisitor();
			// Uses the visitor to get a list of tickets involved in the move.
			List <ScotlandYard.Ticket> allTickets = (List<ScotlandYard.Ticket>) move.accept(tickVis);
			//CLOSE DISTANCE
			// Checks if the closest detective is within one move's distance.
			if(closestDistance <= 1){
				//  If using a double move (indicated by having three tickets), adjust the score
				//  slightly upward, because a double move can be a strategic advantage at close distances.
				if (allTickets.size() == 3) score += 0.75;
				//Because double counts as 3, double, ticket1, ticket2
				// For each ticket, except for the double ticket, adjusts the score based on a predefined value map for close distances.
				for(ScotlandYard.Ticket curr : allTickets){
					if(!curr.equals(ScotlandYard.Ticket.DOUBLE)) score += TicketToValueMapCloseDist(curr);
				}
			}
			//LONG DISTANCE
			else{
				// Handles the case for longer distances from the closest detective.
				if (allTickets.size() == 3) score -= 0.75;
				// Adjusts the score for each ticket based on a value map designed for longer distances.
				for(ScotlandYard.Ticket curr : allTickets){
					if(!curr.equals(ScotlandYard.Ticket.DOUBLE)) score += TicketToValueMapLongDist(curr);
				}
			}
			//The more open the move the better
			// Instantiates a DestinationVisitor to find out the final destination of the move.
			DestinationVisitor destVis = new DestinationVisitor();
			// Uses the visitor to get the destination of the move.
			int dest = (int) move.accept(destVis);
			//Get final destination
			// Iterates over nodes adjacent to the destination. If a detective cannot reach an adjacent node,
			// slightly increases the score, favoring moves that lead to less accessible locations.
			for(int node : board.getSetup().graph.adjacentNodes(dest)){
				if(!canDetectiveReach(board, node)) score += 0.01;
			}
			// If a detective can reach the destination directly,
			// sets the score to zero, indicating a high-risk move.
			if(canDetectiveReach(board, dest)) score = 0;
			//The higher the score, the more likely it is to get picked
			// Returns the calculated score for the move.
			return score;
	}

	// This is the method signature, defining pickMove as a method that returns a Move object.
	// It takes a Board object representing the current game state and a Pair<Long,
	// TimeUnit> which could be used to handle timeouts, although timeout handling is not shown in this snippet.
	@Nonnull @Override public Move pickMove(
			@Nonnull Board board,
			Pair<Long, TimeUnit> timeoutPair) {

		// Retrieves the source location of the first available move for Mr. X.
		// this is used to initialize the location of Mr. X but is later updated within the loop.
		int mrXLoc = board.getAvailableMoves().stream().toList().get(0).source();
		// Retrieves the first player from the board's player list, which is assumed to be Mr. X.
		Piece pieceOfMrx = board.getPlayers().stream().toList().get(0);
		// Fetches a list of all possible moves available for Mr. X from the board.
		var moves = board.getAvailableMoves().asList();
		// Initializes the highest score to -1 to ensure that any move evaluated will have a score greater than this initial value.
		double highestScore = -1;
		// Initializes moveToDo as null, which will later hold the move with the highest score.
		Move moveToDo = null;
		// Casts the Board object to GameState to access game-specific methods like advance.
		Board.GameState state = (Board.GameState) board;
		//  Iterates over each possible move available to Mr. X.
		for(Move move : moves){
			// Applies a move to the current state to generate a new hypothetical game state, used to evaluate the consequences of that move.
			Board.GameState currentState = state.advance(move);
			// Updates Mr. X's location based on whether the move is a SingleMove or DoubleMove. For SingleMove,
			// it sets to the destination of that move; for DoubleMove, it sets to the second destination
			// (ending point of the move).
			if(move instanceof Move.SingleMove){
				mrXLoc = (((Move.SingleMove) move).destination);
			}
			if(move instanceof Move.DoubleMove){
				mrXLoc = (((Move.DoubleMove) move).destination2);
			}
			//ADJUST MRX FOR CHECKING STATES
			// Creates a new Player object representing Mr. X, initialized with default tickets and
			// the updated location from the move being considered.
			Player mrX = new Player(pieceOfMrx, ScotlandYard.defaultMrXTickets(), mrXLoc);
			//Initialises a virtual mrX, will follow a potential mrX, facilitates getting location, tickets, etc...
			// Calculates the score for the current hypothetical game state that results from making the move, using a custom scoring function.
			double currentScore = calculateScore(currentState, mrX, move);
			//  Compares the score of the current move and the highest recorded score. If the current score is higher,
			//  updates highestScore and sets moveToDo to the current move.
			if(currentScore > highestScore){
				highestScore = currentScore;
				moveToDo = move;
			}
		}
		// Ensures that moveToDo is not null, implying that at least one valid move has been found.
		assert moveToDo != null;
		// Returns the move with the highest score, which is deemed the best strategic move for Mr. X at this point in the game.
		return moveToDo;
	}
}
