# Installation Guide for DeSQL

## Prerequisites
This guide will help you set up your environment to use DeSQL for debugging SQL queries in Data-Intensive Scalable Computing (DISC) environments. Ensure all prerequisites are met before proceeding with the installation.

### Software Requirements
- **Docker**: Required to create and manage your debugging environment.
  - Download and install Docker Desktop for your operating system from [Docker Hub](https://www.docker.com/products/docker-desktop/).
- **Python 3**: Required for running scripts and analysis tools.
  - Install Python 3 from [Python.org](https://www.python.org/downloads/).

### Network Configuration
- Ensure that ports 4040 and 8080 are available on your system. These ports are used for the DeSQL UI and Vanilla Spark UI, respectively.

### System Compatibility
- The installation steps have been tested on MacOS (ProductVersion: 11.2.3, BuildVersion: 20D91).
- Docker version 20.10.22 or newer is recommended.

## Setting Up Your Environment

### Step 1: Install Docker
If you haven’t already installed Docker, follow the link provided in the Software Requirements section to download and install the latest version suitable for your operating system.

### Step 2: Verify Python Installation
Ensure Python 3 is installed by running `python3 --version` in your terminal. If Python is not installed, follow the link provided above to install it.

### Step 3: Prepare Network Ports
Check if the required ports (4040 and 8080) are free on your system using the following command:
```bash
sudo lsof -i :4040 -i :8080
```
If these ports are in use, you may need to free them or configure the software to use different ports.

### Step 4: Clone the DeSQL Repository
Clone the DeSQL repository from GitHub to your local machine:
```sh
git clone https://github.com/SEED-VT/DeSQL.git
```
Navigate into the cloned directory:
```sh
cd DeSQL
```

### Step 5: Remove System-Generated Files
Before building the Docker image, clean up any system-generated files that might interfere with Docker builds:
```sh
find . -name '.DS_Store' -type f -delete
```

## Next Steps
After completing these installation steps, you are ready to proceed with building the Docker image and setting up the DeSQL environment as detailed in the `readme.md`.

### Note:
It's essential to ensure that Docker is correctly configured and running on your system before attempting to build and deploy Docker containers as outlined in the `readme.md`.

## Troubleshooting
If you encounter permission issues with Docker commands, using `sudo` may be necessary, or you may need to adjust your Docker settings to run without sudo in Linux environments.

For any issues related to Python packages, ensure that your Python environment is correctly set up and that all dependencies are installed as required.
