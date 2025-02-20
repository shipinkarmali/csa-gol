package stubs

const (
	GeneratePart = "Worker.GeneratePart"
	GenerateTurn = "Broker.GenerateTurn"
	CloseWorker  = "Worker.Close"
	CloseBroker  = "Broker.Close"
	AliveCells   = "Broker.AliveCells"
)

//STRUCTURES

type Params struct {
	Turns       int
	Threads     int
	ImageWidth  int
	ImageHeight int
}
type WorkerRequest struct {
	World    [][]byte
	Params   Params
	X1       int
	X2       int
	Y1       int
	Y2       int
	ClientID string
}
type WorkerResponse struct {
	WorldPart [][]byte
	Complete  bool
}
type BrokerRequest struct {
	World  [][]byte
	Params Params
}
type BrokerResponse struct {
	FinalWorld [][]byte
	Complete   bool
}
type AliveResponse struct {
	Alive bool
}
type TurnRequest struct {
	World  [][]byte
	Params Params
}
type TurnResponse struct {
	World    [][]byte
	Complete bool
}
type TurnPartition struct {
	WorldPart [][]byte
	X1        int
	X2        int
}
type AliveCellsRequest struct {
	World  [][]byte
	Params Params
}
type AliveCellsResponse struct {
	AliveCount int
}
