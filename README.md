# Cloud Simulation using Machine Learning

## Overview
This project simulates a cloud datacenter environment utilizing the CloudSim Plus framework integrated with Machine Learning models. The primary objective is to evaluate and improve task scheduling and resource allocation under uncertainty. By employing machine learning to predict task execution behavior and potential SLA violations, the system optimizes datacenter efficiency, reduces waiting times, and minimizes energy consumption compared to traditional scheduling approaches.

## System Architecture and Framework
The system is built on the robust **CloudSim Plus** framework, extending its core capabilities to interface directly with external Machine Learning endpoints. 

### Key Components
- **Trace Loader:** Ingests and processes real-world Google Borg trace data (`borg_traces_data.csv`) to generate realistic cloudlets (tasks).
- **Datacenter and Hosts:** Simulates a physical datacenter with multiple hosts, tracking CPU, RAM utilization, and energy consumption dynamically.
- **Virtual Machines (VMs):** Handles execution environments for incoming tasks with Space-Shared scheduling.
- **ML Scheduler Broker:** Replaces standard heuristic schedulers. It communicates with an external Machine Learning inference API (served via FastAPI on Hugging Face) to retrieve predictions on task execution before making allocation decisions.

## Machine Learning-Driven Scheduling Strategy
The traditional scheduling algorithm assigns tasks without prior knowledge of their likely execution duration or probability of failure, often leading to unoptimized resource usage. This project solves this by using predictive models.

### Risk-Aware P90 Scheduler
The simulation includes an uncertainty-aware scheduler that utilizes quantile regression. Specifically, it requests the **P90 (90th percentile)** execution prediction from the machine learning model. This ensures that the scheduler provisions enough resources and sets appropriate priority levels even for tasks that have a high variance in their execution times, thereby acting as a risk-aware mechanism.

### Supported ML Models
The framework currently integrates with multiple trained models to predict task behavior, including:
- LightGBM (Default)
- Logistic Regression
- Random Forest
- XGBoost

## Simulation Results & Comparative Analysis
The graphs and results generated from the simulation clearly demonstrate the advantage of the ML-driven approach over traditional cloud scheduling. 

Based on a test load of **10,000 tasks**, the metrics are as follows:

| Metric | Traditional CloudSim | Uncertainty-Aware ML Sim | Improvement |
| :--- | :--- | :--- | :--- |
| **Completed on time** | 6,509 | 9,466 | **+45.4%** |
| **SLA Violations** | 3,491 (34.91%) | 534 (5.34%) | **-84.7%** |
| **Average Waiting Time** | 25.73 units | 4.15 units | **-83.8%** |
| **Energy Consumption** | 0.696 kWh | 0.543 kWh | **-22.0%** |
| **Simulation Time** | 1696.41 s | 1326.86 s | **-21.8%** |

The Uncertainty-Aware simulation accurately provisions resources, significantly dropping SLA violations and minimizing task wait times. This is visually represented in the project's generated graphical output under the `graphs/` directory.

## Project Structure
- `src/main/java/org/cloudsim_ml/` - Contains the Traditional CloudSim Plus scheduling logic.
- `src/main/java/org/cloudsim_uncertainty/` - Contains the Uncertainty-Aware ML framework (P90 Scheduler).
- `cloud-ml-uncertainity.ipynb` & `cloud-simulation-model (2).ipynb` - Jupyter notebooks containing machine learning model training and dataset preparation.
- `graphs/` - Evaluation charts, comparative architecture diagrams, and simulation result logs.
- `borg_traces_data.csv` - The processed Google trace dataset utilized during simulation.

## How to Run
1. Ensure Java is installed in your environment.
2. The project relies on external ML inference. Before running, configure the `HF_PREDICT_URL` and `HF_TOKEN` environment variables to point to your FastAPI inference server.
3. Execute `MainUncertaintySimulation.java` for the ML-driven simulation.
4. Execute `MainSimulation.java` for the traditional baseline comparison.
