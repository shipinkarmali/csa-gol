package gol

// Params provides the details of how to run the Game of Life and which image to load.
type Params struct {
	Turns       int
	Threads     int
	ImageWidth  int
	ImageHeight int
}

// Run starts the processing of Game of Life. It initializes channels and goroutines.
func Run(p Params, events chan<- Event, keyPresses <-chan rune) {

	// Initialize IO channels
	ioFilename := make(chan string)
	ioOutput := make(chan uint8, 10000) // Buffered channel to prevent blocking
	ioInput := make(chan uint8, 10000)

	ioCommand := make(chan ioCommand)
	ioIdle := make(chan bool)

	// Bundle IO channels
	ioChannels := ioChannels{
		command:  ioCommand,
		idle:     ioIdle,
		filename: ioFilename,
		output:   ioOutput,
		input:    ioInput,
	}

	// Start the IO goroutine
	go startIo(p, ioChannels)

	// Bundle distributor channels
	distributorChans := distributorChannels{
		events:     events,
		ioCommand:  ioCommand,
		ioIdle:     ioIdle,
		ioFilename: ioFilename,
		ioOutput:   ioOutput,
		ioInput:    ioInput,
	}

	// Start the distributor
	go distributor(p, distributorChans, keyPresses)
}
