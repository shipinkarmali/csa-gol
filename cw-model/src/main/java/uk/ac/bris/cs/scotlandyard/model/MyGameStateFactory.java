package uk.ac.bris.cs.scotlandyard.model;

import com.google.common.collect.ImmutableList;

import javax.annotation.Nonnull;

import com.google.common.collect.ImmutableSet;
import uk.ac.bris.cs.scotlandyard.model.Board.GameState;
import uk.ac.bris.cs.scotlandyard.model.ScotlandYard.Factory;

import java.util.*;
import java.util.stream.Stream;

/**
 * cw-model
 * Stage 1: Complete this class
 */
public final class MyGameStateFactory implements Factory<GameState> {

	@Nonnull @Override public GameState build(
			GameSetup setup,
			Player mrX,
			ImmutableList<Player> detectives) {
		return new MyGameState(setup, ImmutableSet.of(Piece.MrX.MRX), ImmutableList.of(), mrX, detectives);
		}
		//use LogEntry to create new instance

	static final class MyGameState implements GameState{
		private final GameSetup setup;
		private ImmutableSet<Piece> remaining;
		private final ImmutableList<LogEntry> log;
		private final Player mrX;
		private final List<Player> detectives;
		private final ImmutableSet<Move> moves;
		private ImmutableSet<Piece> winner;

		private static Set<Move.SingleMove> makeSingleMoves(GameSetup setup, List<Player> detectives, Player player, int source) {
			//Initializes an ArrayList to store potential SingleMove objects temporarily
			ArrayList<Move.SingleMove> singleMovesSet = new ArrayList<>();

			// Starts a loop over each node adjacent to the source node.
			// These are the potential destinations for a move from the current position.
			for (int destination : setup.graph.adjacentNodes(source)) {
				// Declares and initializes a boolean flag to check if any
				// detective occupies the destination node, initially set to false.
				boolean destinationOccupied = false;
				// Check if any detective occupies the destination
				// Iterates through each detective to check if any detective is at the destination node.
				// If a detective is found at a destination node, sets destinationOccupied to true and
				// breaks out of the loop to avoid unnecessary checks.
				for (Player detective : detectives) {
					if (detective.location() == destination) {
						destinationOccupied = true;
						break;
					}
				}
				// If the destination is not occupied, generate moves for each transport type
				if (!destinationOccupied) {
					// Iterates over each transportation type that is available between the source and destination node.
					// Uses Objects.requireNonNull to ensure that the result from edgeValueOrDefault
					// (which provides the available transport types or an empty set) is not null.
					for (ScotlandYard.Transport t : Objects.requireNonNull(setup.graph.edgeValueOrDefault(source, destination, ImmutableSet.of()))) {
						//Checks if the player has the required ticket for the current transport or a SECRET ticket, allowing the move.
						if (player.has(t.requiredTicket()) || player.has(ScotlandYard.Ticket.SECRET)) {
							// Creates a SingleMove with the current transport's required ticket and adds it to the list of possible moves.
							Move.SingleMove move = new Move.SingleMove(player.piece(), source, t.requiredTicket(), destination);
							singleMovesSet.add(move);

							// If the player has a SECRET ticket, also add a move with the SECRET ticket
							// Additionally, if the player has a SECRET ticket,
							// it creates another SingleMove using the SECRET ticket for the same route and adds it to the list.
							if (player.has(ScotlandYard.Ticket.SECRET)) {
								Move.SingleMove secretMove = new Move.SingleMove(player.piece(), source, ScotlandYard.Ticket.SECRET, destination);
								singleMovesSet.add(secretMove);
							}
						}
					}
				}
			}
			// Filters out the moves from singleMovesSet for which the player actually has the tickets to perform those moves.
			ArrayList<Move.SingleMove> filteredMoves = new ArrayList<>();
			for (Move.SingleMove move : singleMovesSet) {
				if (player.has(move.ticket)) {
					filteredMoves.add(move);
				}
			}
			// Converts the list of filtered valid moves into an immutable set and returns it,
			// ensuring that the returned set cannot be modified after it's created...
			return ImmutableSet.copyOf(filteredMoves);
		}
		private List<Move> makeDoubleMoves(GameSetup setup, List<Player> detectives, Player player, int source) {
			// Calls the makeSingleMoves method to generate all possible single moves from the source position for the given player.
			// These moves are stored in a list called singleMoves.
			List<Move.SingleMove> singleMoves = new ArrayList<>(makeSingleMoves(setup, detectives, player, source));

            // Initialise new list of allMoves and directly adding all single moves to the final moves list
            List<Move> allMoves = new ArrayList<>(singleMoves);

			// Checks if the player has a DOUBLE ticket available and if there is enough space in the log for a double move.
			// The log must not reach the end of the game setup moves list minus one (since a double move consumes two slots).
			if (player.has(ScotlandYard.Ticket.DOUBLE) && log.size() < setup.moves.size() - 1) {
				// Iterates through each single move generated earlier.
				// Each firstMove represents the first half of a potential double move
				for (Move.SingleMove firstMove : singleMoves) {
					// Generates all possible single moves from the destination of the firstMove.
					// This effectively explores all potential second moves that could follow the first move
					List<Move.SingleMove> secondMoves = new ArrayList<>(makeSingleMoves(setup, detectives, player, firstMove.destination));
					// Iterates through each potential second move to pair with the firstMove.
					for (Move.SingleMove secondMove : secondMoves) {
						// Determines if the combination of firstMove and secondMove is a valid double move.
						// It checks two conditions:
						// If both moves use the same type of ticket, the player must have at least two of those tickets.
						// If the moves use different types of tickets, the player must have at least one of each type used.
						boolean validDoubleMove = firstMove.ticket.equals(secondMove.ticket) && player.hasAtLeast(firstMove.ticket, 2) ||
								!firstMove.ticket.equals(secondMove.ticket) && player.has(firstMove.ticket) && player.has(secondMove.ticket);
						// If the move combination is valid, a DoubleMove is created with the specified parameters and added to the allMoves list.
						// The parameters include the player's piece, the initial source,
						// tickets used for both parts of the move, and their respective destinations.
						if (validDoubleMove) {
							Move.DoubleMove doubleMove = new Move.DoubleMove(player.piece(), source, firstMove.ticket, firstMove.destination, secondMove.ticket, secondMove.destination);
							allMoves.add(doubleMove);
						}
					}
				}
			}

			// No need to filter moves based on ticket possession here as makeSingleMoves already filters based on ticket availability
			return allMoves;
		}


		private MyGameState(
				final GameSetup setup,
				final ImmutableSet<Piece> remaining,
				final ImmutableList<LogEntry> log,
				final Player mrX,
				final List<Player> detectives){

			//Checks if any of the three are null
			if(setup == null || mrX == null || detectives == null){
				throw new NullPointerException("Can't have null!");
			}
			if(setup.graph.nodes().isEmpty() || setup.moves.isEmpty()) {
				throw new IllegalArgumentException("Empty graph/moves");
			}

			// Check repeated location
			for (int i = 0; i < detectives.size(); i++) {
				for (int j = i + 1; j < detectives.size(); j++) {
					if (detectives.get(i).location() == detectives.get(j).location()) {
						throw new IllegalArgumentException("repeated location");
					}
				}
			}
			// Check detectives have secret or double tickets
			for(Player detective : detectives){
				if(detective.tickets().get(ScotlandYard.Ticket.SECRET) != 0)
					throw new IllegalArgumentException("detective has secret");
				if(detective.tickets().get(ScotlandYard.Ticket.DOUBLE) != 0)
					throw new IllegalArgumentException("detective has double");
			}

			this.setup = setup;
			this.remaining = remaining;
			this.log = log;
			this.mrX = mrX;
			this.detectives = detectives;
			this.winner = ImmutableSet.of();
			this.moves = getAvailableMoves();
		}


		@Nonnull
		@Override
		public GameSetup getSetup() {
			return setup;
		}
		@Nonnull
		@Override
		public ImmutableSet<Piece> getPlayers() {
			return Stream.concat(detectives.stream().map(Player::piece),
					Stream.of(mrX.piece())).collect(ImmutableSet.toImmutableSet());
		}
		@Nonnull
		@Override
		public Optional<Integer> getDetectiveLocation(Piece.Detective detective) {
			return detectives.stream()
					.filter(d -> d.piece().equals(detective))
					.findFirst()
					.map(Player::location);
		}

		@Nonnull
		@Override
		public Optional<TicketBoard> getPlayerTickets(Piece piece) {
            return AllPlayers().stream()
					.filter(p -> p.piece().equals(piece))
					.findFirst()
					.map(player -> (TicketBoard) ticket -> player.tickets().getOrDefault(ticket, 0));
		}

		@Nonnull
		@Override
		public ImmutableList<LogEntry> getMrXTravelLog() {
			return log;
		}
		@Nonnull
		@Override
		public ImmutableSet<Piece> getWinner() {
			// Check if Mr. X is caught by any detective.
			boolean mrXCaught = detectives.stream().anyMatch(detective -> detective.location() == mrX.location());
			// Check if Mr. X has no available moves & it's his turn.
			boolean mrXNoMoves = remaining.contains(mrX.piece()) && getAvailableMoves().isEmpty();
			// All detectives are stuck. This checks for detectives who have no tickets of any type.
			boolean allDetectivesOutOfTickets = true;
			for (Player detective : detectives) {
				// If any detective has at least one ticket, then not all detectives are out of tickets.
				if (NumberOfTickets(detective) > 0) {
					allDetectivesOutOfTickets = false;
					break; // Exit as we found a detective with tickets.
				}
			}
			// Determine if all moves have been made, indicating Mr. X survived until the end.
			boolean allMovesMade = log.size() == setup.moves.size();

			// Winning conditions.
			if (mrXCaught || mrXNoMoves) { winner = convertToPieceSet(detectives); }
			if (allMovesMade || allDetectivesOutOfTickets) { winner = ImmutableSet.of(mrX.piece()); }

			// If none of the above conditions are met, it means there's no winner yet.
			return winner;
		}

		@Nonnull
		@Override
		public ImmutableSet<Move> getAvailableMoves() {
			if (!winner.isEmpty()) {
				return ImmutableSet.of(); // No moves available if there's a winner.
			}

			List<Move> allMoves = new ArrayList<>();

			// Generate moves directly for each player that is still in the game
			for (Piece piece : remaining) {
				Player matchingPlayer = AllPlayers().stream()
						.filter(player -> player.piece().equals(piece))
						.findFirst()
						.orElse(null); //  getAllPlayers includes mrX and detectives

				if (matchingPlayer != null) {
					// Single moves are generated for both detectives and Mr. X
					allMoves.addAll(makeSingleMoves(setup, detectives, matchingPlayer, matchingPlayer.location()));

					// Double moves are considered only for Mr. X and if he has a DOUBLE ticket
					if (matchingPlayer.piece() instanceof Piece.MrX && matchingPlayer.has(ScotlandYard.Ticket.DOUBLE)) {
						allMoves.addAll(makeDoubleMoves(setup, detectives, matchingPlayer, matchingPlayer.location()));
					}
				}
			}

			return ImmutableSet.copyOf(allMoves);
		}
		@Nonnull
		@Override
		public GameState advance(Move move) {
			// This checks if the move provided is one of the legal moves available in the current game state.
			// If it's not in the list of allowed moves (moves), it throws an exception, ensuring only valid moves are processed.
			if (!moves.contains(move)) {
				throw new IllegalArgumentException();
			}
			// Initializes a new set that will store the pieces (either Mr. X or detectives)
			// that still have moves left in the current round.
			Set<Piece> newRemaining = new HashSet<>();
			//Checks if the move was commenced by Mr. X.
			// Mr. X has just moved, include all detectives with tickets for the next turn.
			if (move.commencedBy().isMrX()) {
				//Iterates through all detectives. If a detective still has tickets left (implying they can still make moves),
				// their piece is added to the newRemaining set.
				// This prepares the set for the next turn, where only those with available moves are considered active.
				detectives.forEach(detective -> {
					if (NumberOfTickets(detective) > 0) {
						newRemaining.add(detective.piece());
					}
				});

			}
			// Handling moves made by detectives. It checks if more than one detective is still active;
			// if so, it updates the newRemaining set to include all detectives except the one who just moved.
			// If the moving detective was the last active one, it adds Mr. X to the set, indicating it is his turn next.
			else {
				// A detective has moved.
				if (remaining.size() > 1) {
					// If more than one detective remains, remove the moving detective.
					remaining.forEach(piece -> {
						if (!piece.equals(move.commencedBy())) {
							newRemaining.add(piece);
						}
					});
				} else {
					// If this was the last detective, it's now Mr. X's turn.
					newRemaining.add(mrX.piece());
				}
			}
			// Updates the main remaining set to the newly calculated set,
			// making it immutable to ensure the state cannot be altered without going through proper channels (methods)
			remaining = ImmutableSet.copyOf(newRemaining);

			// Declares and initializes an anonymous inner class that implements the Move.Visitor<GameState> interface.
			Move.Visitor<GameState> visitor = new Move.Visitor<>() {
				// Creates a new list for log entries that starts with a copy of the existing game log.
				// This will hold log entries for moves processed during this turn
				final List<LogEntry> newLog = new ArrayList<>(log);

				// Initializes a reference for Mr. X which will be updated as moves are made.
				Player newMrX = mrX;

				// Creates a new list that starts with a copy of the current detectives. This list will be modified if detectives make moves
				final List<Player> newDetectives = new ArrayList<>(detectives);
				//Method to handle the processing of a SingleMove
				@Override
				public GameState visit(Move.SingleMove singleMove) {
					// Checks if the move was made by Mr. X.
					if (singleMove.commencedBy().equals(mrX.piece())) {
						// Mr. X moves
						// Updates Mr. X's location to the move's destination and deducts the appropriate ticket from his ticket book
						newMrX = mrX.at(singleMove.destination).use(singleMove.ticket);
						// Determines whether this move should be visible based on the game setup and the current size of the log
						boolean reveal = setup.moves.get(log.size());
						// Adds a new log entry indicating whether the move is revealed or hidden based on the reveal boolean.
						newLog.add(reveal ? LogEntry.reveal(singleMove.ticket, singleMove.destination) : LogEntry.hidden(singleMove.ticket));
					}

					// A detective moves
					else {
						//  Iterates over the detectives to find the one who made the move
						for (int i = 0; i < detectives.size(); i++) {
							// Checks if the current detective in the loop is the one who made the move.
							if (detectives.get(i).piece().equals(singleMove.commencedBy())) {
								// Updates the detective's ticket book and location
								Player detective = detectives.get(i).use(singleMove.ticket).at(singleMove.destination);
								//Updates the list of detectives with the new state of the moving detective, gives the used ticket to Mr. X, and exits the loop.
								newDetectives.set(i, detective);
								newMrX = mrX.give(singleMove.ticket);
								break;
							}
						}
					}
					// Returns a new GameState reflecting all updates made during the visit.
					return new MyGameState(setup, ImmutableSet.copyOf(remaining), ImmutableList.copyOf(newLog), newMrX, newDetectives);
				}
				// Method to handle the processing of a DoubleMove.
				@Override
				public GameState visit(Move.DoubleMove doubleMove) {
					// Handling Mr. X's double move
					// Updates Mr. X's location to the final destination of the double move and updates his tickets.
					newMrX = mrX.at(doubleMove.destination2).use(doubleMove.tickets());
					// Determines the visibility of each segment of the double move based on the current game setup.
					boolean revealFirstMove = setup.moves.get(log.size());
					boolean revealSecondMove = setup.moves.get(log.size() + 1); // Assuming the setup has sufficient moves to check
					// Adds log entries for both parts of the double move, either as revealed or hidden.
					newLog.add(revealFirstMove ? LogEntry.reveal(doubleMove.ticket1, doubleMove.destination1) : LogEntry.hidden(doubleMove.ticket1));
					newLog.add(revealSecondMove ? LogEntry.reveal(doubleMove.ticket2, doubleMove.destination2) : LogEntry.hidden(doubleMove.ticket2));
					//  Returns a new GameState reflecting all updates made during the visit.
					return new MyGameState(setup, ImmutableSet.copyOf(remaining), ImmutableList.copyOf(newLog), newMrX, newDetectives);
				}
			};
			// The accept method of the move object is called, passing in the visitor.
			// This method will invoke the appropriate visit method depending on whether move is a SingleMove or a DoubleMove,
			// and return the updated GameState.
			return move.accept(visitor);
		}
		private Integer NumberOfTickets(Player p) {
			return p.tickets().values().stream().mapToInt(Integer::intValue).sum();
		}
		//all players in a list
		private List<Player> AllPlayers() {
			return ImmutableList.<Player>builder()
					.addAll(detectives)
					.add(mrX)
					.build();
		}
		private ImmutableSet<Piece> convertToPieceSet(List<Player> players) {
			ImmutableSet.Builder<Piece> builder = ImmutableSet.builder();
			for (Player player : players) {
				builder.add(player.piece());
			}
			return builder.build();
		}
	}
}