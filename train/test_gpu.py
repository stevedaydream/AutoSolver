import torch
import torch.nn.functional as F

def main():
    print(f"PyTorch Version: {torch.__version__}")
    
    # 檢查是否能偵測到 CUDA
    if not torch.cuda.is_available():
        print("錯誤：無法偵測到 CUDA。請確認 NVIDIA 驅動程式已正確安裝。")
        return

    device = torch.device("cuda")
    print(f"目前使用的 GPU: {torch.cuda.get_device_name(device)}")
    print(f"GPU CUDA 能力 (Capability): {torch.cuda.get_device_capability(device)}")

    try:
        # 測試剛剛報錯的 ReLU 運算
        print("正在測試 GPU 張量運算...")
        x = torch.randn(10, 10).to(device)
        y = F.relu(x)
        print("✅ 測試成功！ReLU 運算已順利在 RTX 5070 Ti 上執行，沒有發生崩潰。")
    except Exception as e:
        print(f"❌ 測試失敗，請檢查環境。錯誤訊息：\n{e}")

if __name__ == "__main__":
    main()