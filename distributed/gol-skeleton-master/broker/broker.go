// broker.go
package main

import (
	"fmt"
	"net"
	"net/rpc"
	"os"
	"sync"
	"uk.ac.bris.cs/gameoflife/stubs"
)

type Broker struct {
	Workers         []*rpc.Client
	WorkerMutex     sync.Mutex
	CloseChannel    chan bool
	WorkerAddresses []string // for AWS
}

// NewBroker - make a new Broker and connects it to Workers.
func NewBroker(workerAddresses []string) *Broker {
	broker := &Broker{
		Workers:      []*rpc.Client{},
		CloseChannel: make(chan bool),
	}

	for _, addr := range workerAddresses {
		client, err := rpc.Dial("tcp", addr)
		if err != nil {
			continue
		}
		broker.Workers = append(broker.Workers, client)
		fmt.Printf("Broker is connected to Worker at: %s\n", addr)
	}
	return broker
}

// GenerateTurn handles the simulation for a single turn by coordinating Workers.
func (b *Broker) GenerateTurn(request stubs.TurnRequest, response *stubs.TurnResponse) error {
	world := request.World
	params := request.Params
	threads := params.Threads
	b.WorkerMutex.Lock()
	availableWorkers := len(b.Workers)
	if threads > availableWorkers {
		threads = availableWorkers
	}
	b.WorkerMutex.Unlock()
	var wg sync.WaitGroup
	worldParts := make([][][]byte, threads)
	errChan := make(chan error, threads)

	//  separate the world into parts for each Worker
	partitions := splitWorld(world, threads, params.ImageHeight)

	for i := 0; i < threads; i++ {
		wg.Add(1)
		go func(i int, partition stubs.TurnPartition) {
			defer wg.Done()
			workerRequest := stubs.WorkerRequest{
				World:  world,
				Params: params,
				X1:     partition.X1,
				X2:     partition.X2,
				Y1:     0,
				Y2:     params.ImageWidth,
			}

			var workerRes stubs.WorkerResponse
			err := b.Workers[i].Call(stubs.GeneratePart, workerRequest, &workerRes)
			if err != nil {
				return
			}

			if workerRes.Complete {
				worldParts[i] = workerRes.WorldPart
			}
		}(i, partitions[i])
	}

	wg.Wait()
	close(errChan)

	// Handle any errors from Workers
	for err := range errChan {
		fmt.Println(err)
	}

	// Combine the updated world parts
	updatedWorld := combineWorld(worldParts, threads)

	response.World = updatedWorld
	response.Complete = true
	return nil
}

// AliveCells handles the request for counting alive cells.
func (b *Broker) AliveCells(req stubs.AliveCellsRequest, res *stubs.AliveCellsResponse) error {
	aliveCount := 0
	world := req.World
	for y := 0; y < req.Params.ImageHeight; y++ {
		for x := 0; x < req.Params.ImageWidth; x++ {
			if world[y][x] == 255 {
				aliveCount++
			}
		}
	}
	res.AliveCount = aliveCount
	return nil
}

// Close shuts down the broker
func (b *Broker) Close(req stubs.BrokerRequest, res *stubs.BrokerResponse) error {
	// Send Close command to all Workers
	for _, workerClient := range b.Workers {
		var workerRes stubs.WorkerResponse
		workerReq := stubs.WorkerRequest{}
		err := workerClient.Call(stubs.CloseWorker, workerReq, &workerRes)
		if err != nil {
			return err
		}
	}
	b.CloseChannel <- true
	res.Complete = true
	return nil
}

// split the world into parts for each Worker.
func splitWorld(world [][]byte, threads int, imageHeight int) []stubs.TurnPartition {
	partSize := imageHeight / threads
	partitions := make([]stubs.TurnPartition, threads)

	for i := 0; i < threads; i++ {
		start := i * partSize
		end := start + partSize
		if i == threads-1 {
			end = imageHeight
		}
		partitions[i] = stubs.TurnPartition{
			WorldPart: world[start:end], X1: start, X2: end}
	}
	return partitions
}

// combineWorld merges updated world parts back into the full world matrix.
func combineWorld(worldParts [][][]byte, threads int) [][]byte {
	var newWorld [][]byte

	for i := 0; i < threads; i++ {
		if worldParts[i] != nil {
			newWorld = append(newWorld, worldParts[i]...)
		}
	}

	return newWorld
}

func main() {
	// Define Worker addresses (adjust as needed)
	workerAddresses := []string{
		"172.31.33.74:9000",
		"172.31.38.95:9001",
		"172.31.42.114:9002",
		"172.31.47.162:9003",
	}

	// Initialize the Broker
	broker := NewBroker(workerAddresses)

	// Register Broker's RPC methods
	err := rpc.Register(broker)
	if err != nil {
		fmt.Println("Error registering Broker RPC methods:", err)
		return
	}

	// Listen on a specific port for Distributor's RPC calls
	brokerPort := "8090"
	listener, err := net.Listen("tcp", ":"+brokerPort)
	if err != nil {
		fmt.Println("Error starting Broker listener:", err)
		return
	}
	defer listener.Close()

	fmt.Printf("Broker listening on port %s\n", brokerPort)

	// Accept incoming RPC connections
	go rpc.Accept(listener)

	// Wait for a close signal
	<-broker.CloseChannel

	// Close all Worker connections
	for _, client := range broker.Workers {
		if client != nil {
			client.Close()
		}
	}

	fmt.Println("BROKER SHUTDOWN")
	os.Exit(0)
}
