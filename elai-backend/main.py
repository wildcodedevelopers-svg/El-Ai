import os

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from groq import Groq, APIError

app = FastAPI(
    title="El,ai API",
    description="AI assistant by ELHAM STUDIOS",
    version="1.1.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type"],
)


class ChatRequest(BaseModel):
    message: str = Field(min_length=1, max_length=8000)


class ChatResponse(BaseModel):
    reply: str


SYSTEM_PROMPT = """
You are El,ai, an AI assistant created by ELHAM STUDIOS.

You are intelligent, friendly, practical, and conversational.
Help with learning, coding, creativity, planning, and
problem-solving.

Give clear, useful answers. Be honest when uncertain.
Never claim you performed an action you did not perform.
Keep simple answers concise and explain difficult topics
step by step.
"""


@app.get("/")
def home():
    return {
        "name": "El,ai",
        "creator": "ELHAM STUDIOS",
        "status": "online",
    }


@app.get("/health")
def health():
    return {
        "status": "ok",
        "ai_configured": bool(os.getenv("GROQ_API_KEY")),
    }


@app.post("/ask", response_model=ChatResponse)
def ask(request: ChatRequest):
    message = request.message.strip()

    if not message:
        raise HTTPException(
            status_code=400,
            detail="Please enter a message.",
        )

    api_key = os.getenv("GROQ_API_KEY")

    if not api_key:
        raise HTTPException(
            status_code=503,
            detail="GROQ_API_KEY is missing from Render.",
        )

    try:
        client = Groq(
            api_key=api_key,
            timeout=45.0,
            max_retries=1,
        )

        result = client.chat.completions.create(
            model="openai/gpt-oss-120b",
            messages=[
                {
                    "role": "system",
                    "content": SYSTEM_PROMPT,
                },
                {
                    "role": "user",
                    "content": message,
                },
            ],
            temperature=0.7,
            max_completion_tokens=700,
        )

        reply = result.choices[0].message.content

        if not reply or not reply.strip():
            raise HTTPException(
                status_code=502,
                detail="The AI returned an empty response.",
            )

        return ChatResponse(reply=reply.strip())

    except HTTPException:
        raise

    except APIError as error:
        status = getattr(error, "status_code", None)

        print(
            f"Groq API error: HTTP {status}; "
            f"{type(error).__name__}: {error}"
        )

        if status == 401:
            detail = "Groq rejected the API key. Check GROQ_API_KEY in Render."
        elif status == 429:
            detail = "Groq rate limit reached. Try again later."
        elif status == 404:
            detail = "The selected model is unavailable. Check Groq's model list."
        else:
            detail = (
                f"Groq request failed (HTTP {status}). "
                "Check the Render logs for details."
            )

        raise HTTPException(
            status_code=502,
            detail=detail,
        )

    except Exception as error:
        print(f"Unexpected AI error: {type(error).__name__}")
        raise HTTPException(
            status_code=502,
            detail="Unexpected server error. Check the Render logs.",
        )
