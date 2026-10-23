import os

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field
from groq import Groq, APIError

app = FastAPI(
    title="El,ai API",
    description="AI assistant by ELHAM STUDIOS",
    version="1.0.0",
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

Your personality is intelligent, clear, respectful, practical,
and conversational.

Help users with learning, programming, creativity, planning,
problem-solving, and everyday questions.

Be honest about uncertainty. Never pretend to have performed
actions you have not performed. Keep answers focused and useful.
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
            detail="AI service is not configured yet.",
        )

    try:
        client = Groq(
            api_key=api_key,
            timeout=45.0,
            max_retries=1,
        )

        result = client.chat.completions.create(
            model="llama-3.3-70b-versatile",
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
            max_tokens=700,
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
        # Do not expose provider errors or secret keys.
        print(
            f"Groq API error: "
            f"{getattr(error, 'status_code', 'unknown')}"
        )
        raise HTTPException(
            status_code=502,
            detail="The AI provider could not complete the request.",
        )

    except Exception as error:
        print(f"Unexpected AI error: {type(error).__name__}")
        raise HTTPException(
            status_code=502,
            detail="An unexpected AI error occurred.",
        )
